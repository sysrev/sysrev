(ns sysrev.source.interface
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
  Returns true on success, false on failure. Catches all exceptions,
  indicating failure in return value."
  [project-id source-id {:keys [article-refs get-articles] :as impl}]
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
          (log/warn "import-articles-impl: error importing group -" (.getMessage e))
          (throw e))))
    true
    (catch Throwable e
      (log/warn "error in import-pmids-to-project:" (.getMessage e))
      (.printStackTrace e)
      false)
    (finally
      (clear-project-cache project-id))))

(defn- import-source-articles
  "Implements multi-threaded import of articles from a new project-source.
  Returns true on success, false on failure. Catches all exceptions,
  indicating failure in return value."
  [project-id source-id {:keys [article-refs get-articles] :as impl} threads]
  (if (and (> threads 1) (nil? *conn*))
    (try
      (let [group-size (->> (quot (count article-refs) threads) (max 1))
            thread-groups (->> article-refs (partition-all group-size))
            thread-results
            (->> thread-groups
                 (mapv
                  (fn [thread-refs]
                    (future (import-articles-impl
                             project-id source-id
                             {:article-refs thread-refs
                              :get-articles get-articles}))))
                 (mapv deref))]
        (every? true? thread-results))
      (catch Throwable e
        (log/warn "Error in import-source-articles:" (.getMessage e))
        false))
    (import-articles-impl project-id source-id impl)))

(defn- after-source-import
  "Handles success or failure after an import attempt has finished."
  [project-id source-id success?]
  (with-transaction
    ;; update source metadata
    (if success?
      (s/alter-source-meta
       source-id #(assoc % :importing-articles? false))
      (s/fail-source-import source-id))
    ;; update the enabled flag for the articles
    (s/update-project-articles-enabled project-id))
  ;; start threads for updates from api.insilica.co
  (when success?
    (predict-api/schedule-predict-update project-id)
    (importance/schedule-important-terms-update project-id))
  success?)

(defn import-source-impl
  "Top-level function for running import of a new source with
  articles. This should only be called directly from an import-source
  method implementation for a source type. get-article-refs is a
  function returning a sequence of values (article-refs) that can be
  used to obtain an article; get-articles is a function that accepts a
  sequence of values from article-refs and returns their corresponding
  article values."
  [project-id source-meta
   {:keys [get-article-refs get-articles] :as impl}
   {:keys [use-future? threads] :or {use-future? true threads 4}}]
  (let [blocking? (boolean (or (not use-future?) *conn*))
        source-id (s/create-source
                   project-id (assoc source-meta :importing-articles? true))
        do-import (fn []
                    (->> (try (import-source-articles
                               project-id source-id
                               {:article-refs (get-article-refs)
                                :get-articles get-articles}
                               threads)
                              (catch Throwable e
                                (log/warn "import-source-impl failed -" (.getMessage e))
                                false))
                         (after-source-import project-id source-id)))]
    {:source-id source-id
     :import (if blocking? (do-import) (future (do-import)))}))

(defmulti import-source
  "Multimethod for import implementation per source type."
  (fn [stype project-id input options] stype))

(defmethod import-source :default [stype project-id input options]
  (throw (Exception. (format "import-source - invalid source type (%s)"
                             (pr-str stype)))))
