(ns sysrev.source.import
  (:require [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction *conn*
              clear-project-cache]]
            [sysrev.db.articles :as articles]
            [sysrev.source.core :as s]
            [sysrev.shared.util :as su :refer [in?]]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.biosource.importance :as importance]))

(defn- add-articles
  "Implements adding article entries to db from sequence of article maps.

  Returns map of {article-id -> article} where article is the article
  map used as source to create the db entry."
  [project-id articles]
  (let [prepare-article #(-> % (dissoc :locations) (assoc :enabled false))
        article-ids (articles/add-articles (->> articles (mapv prepare-article))
                                           project-id *conn*)]
    (->> (map (fn [id article] {id article})
              article-ids articles)
         (apply merge))))

(defn- match-existing-articles
  "Implements checking list of article maps for matches against existing
  project articles."
  [project-id articles]
  (let [ ;; check for matches of PMID value
        public-ids (->> articles (map :public-id) (remove nil?) (mapv str))
        existing-articles
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
        have-article? #(some->> % :public-id (in? existing-public-ids))]
    {:new-articles (->> articles (remove have-article?))
     :existing-article-ids existing-article-ids}))

(defn- import-articles-impl
  "Implements single-threaded import of articles from a new project-source.
  Returns true on success, false on failure.
  Catches all exceptions, indicating failure in return value."
  [project-id source-id {:keys [article-refs get-articles] :as input}]
  (try
    (doseq [articles (->> (get-articles article-refs) (partition-all 10))]
      (try
        (with-transaction
          (let [{:keys [new-articles existing-article-ids]}
                (match-existing-articles project-id articles)
                new-article-ids (add-articles project-id new-articles)
                new-locations
                (->> (keys new-article-ids)
                     (map (fn [article-id]
                            (let [article (get new-article-ids article-id)]
                              (->> (:locations article)
                                   (mapv #(assoc % :article-id article-id))))))
                     (apply concat)
                     vec)]
            (s/add-articles-to-source
             (concat existing-article-ids (keys new-article-ids))
             source-id)
            (when (not-empty new-locations)
              (-> (sqlh/insert-into :article-location)
                  (values new-locations)
                  do-execute))))
        (catch Throwable e
          (log/info "import-articles-impl: error importing group -" (.getMessage e))
          (throw e))))
    true
    (catch Throwable e
      (log/info (str "error in import-pmids-to-project: "
                     (.getMessage e)))
      (.printStackTrace e)
      false)
    (finally
      (clear-project-cache project-id))))

(defn- import-source-articles
  "Implements multi-threaded import of articles from a new project-source.
  Returns true on success, false on failure.
  Catches all exceptions, indicating failure in return value."
  [project-id source-id {:keys [article-refs get-articles] :as input}
   & {:keys [use-future? threads] :or {use-future? false threads 1}}]
  (if (and use-future? (nil? *conn*))
    nil
    (try
      (import-articles-impl project-id source-id input)
      (catch Throwable e
        (log/info "Error in import-source-articles:"
                  (.getMessage e))
        false)))
  #_
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
            (s/update-source-meta
             source-id (assoc meta :importing-articles? false))
            (s/fail-source-import source-id))
          ;; update the enabled flag for the articles
          (s/update-project-articles-enabled project-id))
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
          (s/update-source-meta
           source-id (assoc meta :importing-articles? false))
          (s/fail-source-import source-id))
        ;; update the enabled flag for the articles
        (s/update-project-articles-enabled project-id))
      ;; start threads for updates from api.insilica.co
      (when success?
        (predict-api/schedule-predict-update project-id)
        (importance/schedule-important-terms-update project-id))
      success?)))

(defn- import-source
  [project-id source-id {:keys [article-refs get-articles] :as input}
   & {:keys [use-future? threads] :or {use-future? false threads 1}}]
  (let [success? (import-source-articles
                  project-id source-id input
                  :use-future? use-future? :threads threads)]
    (with-transaction
      ;; update source metadata
      (if success?
        (s/update-source-meta
         source-id (assoc meta :importing-articles? false))
        (s/fail-source-import source-id))
      ;; update the enabled flag for the articles
      (s/update-project-articles-enabled project-id))
    ;; start threads for updates from api.insilica.co
    (when success?
      (predict-api/schedule-predict-update project-id)
      (importance/schedule-important-terms-update project-id))
    success?))

#_
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

#_
(defn- import-pmids-to-project
  "Imports into project all articles referenced in list of PubMed IDs.
  Note that this will not import an article if the PMID already exists
  in the project."
  [project-id source-id {:keys [article-refs get-articles] :as input}]
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
