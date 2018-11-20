(ns sysrev.import.pubmed
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.data.xml :as dxml]
            [clojure-csv.core :as csv]
            [clj-http.client :as http]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction clear-project-cache to-jsonb *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.db.sources :as sources]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.biosource.importance :as importance]
            [sysrev.pubmed :as pm]
            [sysrev.util :as u]
            [sysrev.shared.util :as su :refer [in? map-values]]))

(defn- import-pmids-to-project
  "Imports into project all articles referenced in list of PubMed IDs.
  Note that this will not import an article if the PMID already exists
  in the project."
  [pmids project-id source-id]
  (try
    (doseq [pmids-group (->> pmids sort
                             (partition-all (if pm/use-cassandra-pubmed? 300 40)))]
      (let [group-articles (->> pmids-group
                                (#(if pm/use-cassandra-pubmed?
                                    (pm/fetch-pmid-entries-cassandra %)
                                    (pm/fetch-pmid-entries %)))
                                (remove nil?))]
        (doseq [articles (->> group-articles (partition-all 10))]
          (let [public-ids (->> articles (map :public-id) (remove nil?) (mapv str))]
            (try
              (with-transaction
                (let [existing-articles
                      (if (empty? public-ids)
                        []
                        (-> (select :article-id :public-id)
                            (from :article)
                            (where [:and
                                    [:= :project-id project-id]
                                    [:in :public-id public-ids]])
                            (->> do-query vec)))
                      existing-article-ids
                      (->> existing-articles (mapv :article-id))
                      existing-public-ids
                      (->> existing-articles (mapv :public-id) (filterv not-empty))
                      new-articles
                      (->> articles
                           (filter #(not-empty (:primary-title %)))
                           (filter :public-id)
                           (remove #(in? existing-public-ids (:public-id %))))
                      new-article-ids
                      (->> (map (fn [id article] {id article})
                                (articles/add-articles
                                 (->> new-articles
                                      (mapv #(-> %
                                                 (dissoc :locations)
                                                 (assoc :enabled false))))
                                 project-id *conn*)
                                new-articles)
                           (apply merge))
                      new-locations
                      (->> (keys new-article-ids)
                           (map (fn [article-id]
                                  (let [article (get new-article-ids article-id)]
                                    (->> (:locations article)
                                         (mapv #(assoc % :article-id article-id))))))
                           (apply concat)
                           vec)]
                  (sources/add-articles-to-source
                   (concat existing-article-ids (keys new-article-ids))
                   source-id)
                  (when (not-empty new-locations)
                    (-> (sqlh/insert-into :article-location)
                        (values new-locations)
                        do-execute))))
              (catch Throwable e
                (log/info "error importing pmids group:" (.getMessage e))
                (throw e)))))))
    true
    (catch Throwable e
      (log/info (str "error in import-pmids-to-project: "
                     (.getMessage e)))
      (.printStackTrace e)
      false)
    (finally
      (clear-project-cache project-id))))

(defn import-pmids-to-project-with-meta!
  "Import articles into project-id using the meta map as a source description. If the optional keyword :use-future? true is used, then the importing is wrapped in a future"
  [pmids project-id meta & {:keys [use-future? threads]
                            :or {use-future? false threads 1}}]
  (let [source-id (sources/create-source
                   project-id (assoc meta :importing-articles? true))]
    (if (and use-future? (nil? *conn*))
      (future
        (let [success?
              (try
                (let [thread-groups
                      (->> pmids
                           (partition-all (max 1 (quot (count pmids) threads))))
                      thread-results
                      (->> thread-groups
                           (mapv
                            (fn [thread-pmids]
                              (future
                                (try
                                  (import-pmids-to-project
                                   thread-pmids project-id source-id)
                                  (catch Throwable e
                                    (log/info "Error in import-pmids-to-project-with-meta! (inner future)"
                                              (.getMessage e))
                                    false)))))
                           (mapv deref))]
                  (every? true? thread-results))
                (catch Throwable e
                  (log/info "Error in import-pmids-to-project-with-meta! (outer future)"
                            (.getMessage e))
                  false))]
          (with-transaction
            ;; update source metadata
            (if success?
              (sources/update-source-meta
               source-id (assoc meta :importing-articles? false))
              (sources/fail-source-import source-id))
            ;; update the enabled flag for the articles
            (sources/update-project-articles-enabled project-id))
          ;; start threads for updates from api.insilica.co
          (when success?
            (predict-api/schedule-predict-update project-id)
            (importance/schedule-important-terms-update project-id))
          success?))
      (let [success?
            (try
              (import-pmids-to-project pmids project-id source-id)
              (catch Throwable e
                (log/info "Error in import-pmids-to-project-with-meta!"
                          (.getMessage e))
                false))]
        (with-transaction
          ;; update source metadata
          (if success?
            (sources/update-source-meta
             source-id (assoc meta :importing-articles? false))
            (sources/fail-source-import source-id))
          ;; update the enabled flag for the articles
          (sources/update-project-articles-enabled project-id))
        ;; start threads for updates from api.insilica.co
        (when success?
          (predict-api/schedule-predict-update project-id)
          (importance/schedule-important-terms-update project-id))
        success?))))

(defn load-pmids-file
  "Loads a list of integer PubMed IDs from a linebreak-separated text file."
  [path]
  (->> path io/file io/reader
       csv/parse-csv
       (mapv (comp #(Integer/parseInt %) first))))

(defn parse-pmid-file
  "Loads a list of integer PubMed IDs from a file. PMIDs can be separated by commas and white space. Removes duplicates"
  [file]
  (try (->> (-> (slurp file)
                (str/split #"(\s+|,)"))
            (filterv (comp not empty?))
            (mapv (comp #(Integer/parseInt %)))
            distinct
            (apply vector))
       (catch Throwable e
         (log/info "Bad Format " (.getMessage e))
         nil)))

(defn import-from-pmids-file
  "Imports articles from PubMed API into project from linebreak-separated text
  file of PMIDs."
  [project-id path]
  (import-pmids-to-project (load-pmids-file path) project-id))

