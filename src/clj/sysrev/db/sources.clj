(ns sysrev.db.sources
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [values insert-into where sset from select
                                               delete-from merge-where group left-join
                                               having]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer
             [do-query do-execute clear-project-cache with-transaction with-project-cache]]
            [sysrev.db.queries :as q]))

(defn source-id->project-id
  [source-id]
  (-> (select :project-id)
      (from :project-source)
      (where [:= :source-id source-id])
      do-query first :project-id))
;;
(s/fdef source-id->project-id
        :args (s/cat :source-id int?)
        :ret int?)

(defn create-project-source-metadata!
  "Create a project_source table entry with a metadata map for project-id"
  [project-id metadata]
  (try
    (-> (insert-into :project-source)
        (values [{:meta metadata
                  :project-id project-id}])
        (returning :source-id)
        do-query first :source-id)
    (finally
      (clear-project-cache project-id))))
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
  [search-term search-count]
  {:source "PubMed search"
   :search-term search-term
   :search-count search-count})
;;
(s/fdef import-pmids-search-term-meta
        :args (s/cat :search-term string?
                     :search-count int?)
        :ret map?)

(def import-facts-meta
  {:source "facts"})

(def legacy-source-meta
  {:source "legacy"})

(defn update-project-source-metadata!
  "Replace the metadata for source-id"
  [source-id metadata]
  (try
    (-> (sqlh/update :project-source)
        (sset {:meta metadata})
        (where [:= :source-id source-id])
        do-execute)
    (finally
      (clear-project-cache (source-id->project-id source-id)))))
;;
(s/fdef update-project-source-metadata!
        :args (s/cat :source-id int?
                     :metadata map?))

(defn alter-project-source-metadata!
  "Replaces the meta field for source-id with the result of
  applying function f to the existing value."
  [source-id f]
  (when-let [meta (-> (select :meta)
                      (from :project-source)
                      (where [:= :source-id source-id])
                      do-query first :meta)]
    (update-project-source-metadata! source-id (f meta))))
;;
(s/fdef alter-project-source-metadata!
        :args (s/cat :source-id int?
                     :f ifn?))

(defn delete-project-source-articles!
  "Deletes all article-source entries for source-id, and their associated
  article entries unless contained in another source."
  [source-id]
  (try
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
          do-execute))
    (finally
      (clear-project-cache (source-id->project-id source-id)))))

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
  (try
    (with-transaction
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
                      (from [:article-source :ars])
                      (left-join [:project-source :ps]
                                 [:= :ars.source-id :ps.source-id])
                      (where [:and
                              [:= :ps.project-id project-id]
                              [:= :article.article-id :ars.article-id]
                              [:= :ps.enabled true]]))])
          do-execute)
      ;; check the article_flags table as the ultimate truth
      ;; for the enabled setting
      (-> (sqlh/update :article)
          (sset {:enabled false})
          (where [:exists
                  (-> (select :*)
                      (from [:article-flag :af])
                      (where [:and
                              [:= :af.article-id :article.article-id]
                              [:= :af.disable true]]))])
          do-execute))
    (finally
      (clear-project-cache project-id))))
;;
(s/fdef update-project-articles-enabled!
        :args (s/cat :project-id int?))

(defn delete-project-source!
  "Given a source-id, delete it and remove the articles associated with
  it from the database.  Warning: This fn doesn't care if there are
  labels associated with an article"
  [source-id]
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

(defn source-articles-with-labels
  "Given a source-id, return the amount of articles that have labels"
  [source-id]
  (let [project-id (source-id->project-id source-id)]
    (-> (q/select-project-articles project-id [:%count.*] {:include-disabled-source? true})
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

(defn source-unique-articles-count
  "Given a project-id, return the amount of articles that are unique to a source"
  [project-id]
  (let [sources (-> (select :source-id)
                    (from :project-source)
                    (where [:and
                            [:= :project-id project-id]
                            [:= :enabled true]]))
        project-sources (-> sources
                            do-query)
        articles (-> (select :*)
                     (from [:article-source :ars])
                     (where [:in :source-id sources])
                     do-query)]
    (map (fn [project-source]
           {:source-id (:source-id project-source)
            :unique-articles-count (->> articles
                                        (group-by :article-id)
                                        (into '())
                                        (filter #(= (count (second %)) 1))
                                        (filter #(= (-> % second first :source-id) (:source-id project-source)))
                                        count)})
         project-sources)))

(defn project-source-overlap
  "Given a project-id and base-source-id, determine the amount of articles that overlap with source-id.
  The source associated with source-id must be enabled, otherwise the overlap is ignored and this fn
  will return 0"
  [project-id base-source-id source-id]
  (count (-> (select :%count.*)
             (from [:article-source :ars])
             (left-join [:project-source :ps]
                        [:= :ars.source-id :ps.source-id])
             (where [:and
                     [:= :ps.project-id project-id]
                     [:= :ps.enabled true]
                     [:or
                      [:= :ps.source-id base-source-id]
                      [:= :ps.source-id source-id]]])
             (group :ars.article-id)
             (having [:> :%count.* 1])
             do-query)))
;;
(s/fdef project-source-overlap
        :args (s/cat :project-id int?
                     :base-source-id int?
                     :source-id int?)
        :ret int?)

(defn project-sources-overlap
  "Given a project-id, determine the overlap of all enabled sources"
  [project-id]
  (filter #(not= (:source-id %) (:overlap-source-id %))
          (for [base-sources (mapv :source-id (-> (select :ps.source-id)
                                                  (from [:project-source :ps])
                                                  (where [:= :ps.project-id project-id])
                                                  do-query))
                sources (mapv :source-id (-> (select :ps.source-id)
                                             (from [:project-source :ps])
                                             (where [:= :ps.project-id project-id])
                                             do-query))]
            {:source-id base-sources
             :overlap-source-id sources
             :count (project-source-overlap project-id base-sources sources)})))
;;
(s/fdef project-sources-overlap
        :args (s/cat :project-id int?)
        :ret coll?)

(defn project-sources
  "Given a project-id, return the corresponding vectors of
  project-source data or nil if it does not exist"
  [project-id]
  (with-project-cache
    project-id [:sources :all]
    (let [overlap-coll (project-sources-overlap project-id)
          unique-coll (source-unique-articles-count project-id)]
      (-> (select :ps.source-id
                  :ps.project-id
                  :ps.meta
                  :ps.enabled
                  :ps.date-created)
          (from [:project-source :ps])
          (where [:= :ps.project-id project-id])
          ;; associate article-count and labeled counts
          (->> do-query
               (mapv
                (fn [{:keys [source-id] :as psource}]
                  (let [article-count
                        (-> (q/select-project-articles project-id [:%count.*] {:include-disabled-source? true})
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
                               :labeled-article-count labeled-count)))))
               ;; include the amount of overlap between sources
               (mapv (fn [source]
                       (assoc source :overlap (->> overlap-coll
                                                   (filter #(= (:source-id %)
                                                               (:source-id source)))
                                                   (mapv #(select-keys % [:overlap-source-id :count]))))))
               ;; include the unique sources in each
               (mapv (fn [source]
                       (assoc source :unique-articles-count (->> unique-coll
                                                                 (filter #(= (:source-id %)
                                                                             (:source-id source)))
                                                                 first
                                                                 :unique-articles-count)))))))))
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
  (-> (sqlh/insert-into :article-source)
      (values [{:article-id article-id
                :source-id source-id}])
      do-execute))

(defn source-exists?
  "Does a source with source-id exist?"
  [source-id]
  (= source-id
     (-> (select :source-id)
         (from [:project-source :ps])
         (where [:= :ps.source-id source-id])
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
  (with-transaction
    (-> (sqlh/update :project-source)
        (sset {:enabled enabled?})
        (where [:= :source-id source-id])
        do-execute)
    ;; logic for updating enabled in article
    (update-project-articles-enabled! (source-id->project-id source-id))))
;;
(s/fdef toggle-source!
        :args (s/cat :source-id int?
                     :enabled? boolean?))
