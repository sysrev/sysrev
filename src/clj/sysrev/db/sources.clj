(ns sysrev.db.sources
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [values insert-into where sset from select
                                               delete-from merge-where group left-join]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute clear-query-cache with-transaction]]
            [sysrev.db.queries :as q]))

(defn source-id->project-id
  [source-id]
  (-> (select :project_id)
      (from :project_source)
      (where [:= :source_id source-id])
      do-query
      first
      :project-id))

(s/fdef source-id->project-id
        :args (s/cat :source-id int?)
        :ret int?)

(defn create-project-source-metadata!
  "Create a project_source table entry with a metadata map for project-id"
  [project-id metadata]
  (-> (insert-into :project_source)
      (values [{:meta metadata
                :project-id project-id}])
      (returning :source_id)
      do-query first :source-id))
;;
(s/fdef create-project-source-metadata!
        :args (s/cat :project-id int?
                     :metadata map?)
        :ret int?)

(def import-pmids-meta
  {:source "PMID vector"})

(defn import-pmids-from-filename-meta
  [filename]
  {:source "PMID file"
   :filename filename})

(defn import-articles-from-endnote-file-meta
  [filename]
  {:source "EndNote file"
   :filename filename})

(defn import-pmids-search-term-meta
  [search-term]
  {:source "PubMed search"
   :search-term search-term})
;;
(s/fdef import-pmids-search-term-meta
        :args (s/cat :search-term string?)
        :ret map?)

(def import-facts-meta
  {:source "facts"})

(def legacy-source-meta
  {:source "legacy"})

(defn update-project-source-metadata!
  "Replace the metadata for project-source-id"
  [project-source-id metadata]
  (-> (sqlh/update :project_source)
      (sset {:meta metadata})
      (where [:= :source_id project-source-id])
      do-execute))
;;
(s/fdef update-project-source-metadata!
        :args (s/cat :project-source-id int?
                     :metadata map?))

(defn alter-project-source-metadata!
  "Replaces the meta field for project-source-id with the result of
  applying function f to the existing value."
  [project-source-id f]
  (when-let [meta (-> (select :meta)
                      (from :project-source)
                      (where [:= :source-id project-source-id])
                      do-query first :meta)]
    (update-project-source-metadata! project-source-id (f meta))))
;;
(s/fdef alter-project-source-metadata!
        :args (s/cat :project-source-id int?
                     :f ifn?))

(defn delete-project-source-articles!
  "Deletes all article-source entries for source-id, and their associated
  article entries unless contained in another source."
  [source-id]
  (clear-query-cache)
  (with-transaction
    (-> (delete-from [:article :a])
        (where (let [asources
                     (-> (select :*)
                         (from [:article-source :asrc])
                         (where [:= :asrc.article-id :a.article-id]))]
                 [:and
                  [:exists
                   (-> asources
                       (merge-where
                        [:= :asrc.source-id source-id]))]
                  [:not
                   [:exists
                    (-> asources
                        (merge-where
                         [:!= :asrc.source-id source-id]))]]]))
        do-execute)
    (-> (delete-from :article-source)
        (where [:= :source-id source-id])
        do-execute)))

(defn fail-project-source-import!
  "Update database in response to an error during the import process
  for a project-source."
  [source-id]
  (alter-project-source-metadata!
   source-id #(assoc % :importing-articles? :error))
  (delete-project-source-articles! source-id))

(defn update-project-articles-enabled!
  "Update the enabled fields of articles associated with project-id."
  [project-id]
  (do
    ;; set all articles enabled to false
    (-> (sqlh/update :article)
        (sset {:enabled false})
        (where [:= :project-id project-id])
        do-execute)
    ;; set all articles enabled to true for which they have a source
    ;; that is enabled
    (-> (sqlh/update :article)
        (sset {:enabled true})
        (where [:exists
                (-> (select :*)
                    (from [:article_source :ars])
                    (left-join [:project_source :ps]
                               [:= :ars.source_id :ps.source_id])
                    (where [:and
                            [:= :ps.project_id project-id]
                            [:= :article.article_id :ars.article_id]
                            [:= :ps.enabled true]]))])
        do-execute)
    ;; check the article_flags table as the ultimate truth
    ;; for the enabled setting
    (-> (sqlh/update :article)
        (sset {:enabled true})
        (where [:exists
                (-> (select :*)
                    (from [:article_flag :af])
                    (where [:and
                            [:= :af.article_id :article.article_id]
                            [:= :af.disable false]]))])
        do-execute)))

(s/fdef update-project-articles-enabled!
        :args (s/cat :project-id int?))

(defn delete-project-source!
  "Given a source-id, delete it and remove the articles associated with
  it from the database.  Warning: This fn doesn't care if there are
  labels associated with an article"
  [source-id]
  (clear-query-cache)
  (alter-project-source-metadata!
   source-id #(assoc % :deleting? true))
  (let [project-id (source-id->project-id source-id)]
    (try (with-transaction
           ;; delete articles that aren't contained in another source
           (delete-project-source-articles! source-id)
           ;; delete entries for project source
           (-> (delete-from :project-source)
               (where [:= :source-id source-id])
               do-execute)
           true)
         (catch Throwable e
           (log/info "Caught exception in sysrev.db.sources/delete-project-source!: "
                     (.getMessage e))
           (alter-project-source-metadata!
            source-id #(assoc % :deleting? false))
           false)
         (finally
           ;; update the article enabled flags
           (update-project-articles-enabled! project-id)))))

;;
(s/fdef delete-project-source!
        :args (s/cat :source-id int?))

(defn project-id-from-source-id [source-id]
  (-> (select :project-id)
      (from :project-source)
      (where [:= :source-id source-id])
      do-query first :project-id))

(defn source-articles-with-labels
  "Given a source-id, return the amount of articles that have labels"
  [source-id]
  (let [project-id (project-id-from-source-id source-id)]
    ;; TODO: update select-project-articles call after changes to enabled logic
    (-> (q/select-project-articles project-id [:%count.*])
        (merge-where
         [:exists
          (-> (select :*)
              (from [:article-source :asrc])
              (where [:and
                      [:= :asrc.article-id :a.article-id]
                      [:= :asrc.source-id source-id]]))])
        (merge-where
         [:exists
          (-> (select :*)
              (from [:article-label :al])
              (where [:and
                      [:= :al.article-id :a.article-id]
                      [:!= :al.answer nil]
                      [:!= :al.confirm-time nil]]))])
        do-query first :count)))
;;
(s/fdef source-articles-with-labels
        :args (s/cat :project-id int?)
        :ret (s/nilable int?))

(defn project-sources
  "Given a project-id, return the corresponding vectors of
  project-source data or nil if it does not exist"
  [project-id]
  (-> (select :ps.source-id
              :ps.project-id
              :ps.meta
              :ps.date-created)
      (from [:project_source :ps])
      (where [:= :ps.project_id project-id])
      (->> do-query
           (mapv
            (fn [{:keys [source-id] :as psource}]
              (let [article-count
                    ;; TODO: update select-project-articles call after changes to enabled logic
                    (-> (q/select-project-articles project-id [:%count.*])
                        (merge-where
                         [:exists
                          (-> (select :*)
                              (from [:article-source :asrc])
                              (where [:and
                                      [:= :asrc.article-id :a.article-id]
                                      [:= :asrc.source-id source-id]]))])
                        do-query first :count)
                    labeled-count (source-articles-with-labels source-id)]
                (-> psource
                    (assoc :article-count article-count
                           :labeled-article-count labeled-count))))))))
;;
(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret (s/nilable vector?))

(defn source-has-labeled-articles?
  [source-id]
  (not (zero? (source-articles-with-labels source-id))))
;;
(s/fdef source-has-labeled-articles?
        :args (s/cat :source-id int?)
        :ret boolean?)

(defn add-article-to-source!
  "Add article-id to source-id"
  [article-id source-id]
  (-> (sqlh/insert-into :article_source)
      (values [{:article_id article-id
                :source_id source-id}])
      do-execute))

(defn source-exists?
  "Does a source with source-id exist?"
  [source-id]
  (= source-id
     (-> (select :source-id)
         (from [:project_source :ps])
         (where [:= :ps.source_id source-id])
         do-query
         first
         :source-id)))
;;
(s/fdef source-exists?
        :args (s/cat :project-id int?)
        :ret boolean?)

(defn toggle-source!
  "Toggle a source has enabled?"
  [source-id enabled?]
  (-> (sqlh/update :project_source)
      (sset {:enabled enabled?})
      (where [:= :source_id source-id])
      do-execute)
  ;; logic for updating enabled in article
  (update-project-articles-enabled! (source-id->project-id source-id)))

;;
(s/fdef toggle-source!
        :args (s/cat :source-id int?
                     :enabled? boolean?))
