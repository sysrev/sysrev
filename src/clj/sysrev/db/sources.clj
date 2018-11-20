(ns sysrev.db.sources
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-jsonb
              clear-project-cache with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.shared.util :as su :refer [in? map-values]]))

(def all-source-types
  [:pubmed :pmid-vector :pmid-file :endnote-xml :pdf-zip
   :legacy :custom])

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

(defn create-source
  "Create an entry in project_source table"
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
(s/fdef create-source
        :args (s/cat :project-id int?
                     :metadata map?)
        :ret int?)

(defmulti make-source-meta (fn [source-type values] source-type))

(defmethod make-source-meta :default [source-type values]
  (throw (Exception. "invalid source-type")))

(defmethod make-source-meta :pubmed [_ {:keys [search-term search-count]}]
  {:source "PubMed search"
   :search-term search-term
   :search-count search-count})

(defmethod make-source-meta :pmid-vector [_ {:keys []}]
  {:source "PMID vector"})

(defmethod make-source-meta :pmid-file [_ {:keys [filename]}]
  {:source "PMID file" :filename filename})

(defmethod make-source-meta :endnote-xml [_ {:keys [filename]}]
  {:source "EndNote file" :filename filename})

(defmethod make-source-meta :pdf-zip [_ {:keys [filename]}]
  {:source "PDF Zip file" :filename filename})

(defmethod make-source-meta :legacy [_ {:keys []}]
  {:source "legacy"})

(defmethod make-source-meta :custom [_ {:keys [description]}]
  {:source "Custom import" :custom description})

(defn update-source-meta
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
(s/fdef update-source-meta
        :args (s/cat :source-id int?
                     :metadata map?))

(defn alter-source-meta
  "Replaces the meta field for source-id with the result of
  applying function f to the existing value."
  [source-id f]
  (when-let [meta (-> (select :meta)
                      (from :project-source)
                      (where [:= :source-id source-id])
                      do-query first :meta)]
    (update-source-meta source-id (f meta))))
;;
(s/fdef alter-source-meta
        :args (s/cat :source-id int?
                     :f ifn?))

(defn delete-source-articles
  "Deletes all article-source entries for source-id, and their associated
  article entries unless contained in another source."
  [source-id]
  (with-transaction
    (try
      (let [project-id (source-id->project-id source-id)
            asources (-> (select :*)
                         (from [:article-source :asrc])
                         (where [:= :asrc.article-id :a.article-id]))]
        (-> (delete-from [:article :a])
            (where [:and
                    [:= :a.project-id project-id]
                    [:exists
                     (-> asources
                         (merge-where
                          [:= :asrc.source-id source-id]))]
                    [:not
                     [:exists
                      (-> asources
                          (merge-where
                           [:!= :asrc.source-id source-id]))]]])
            do-execute)
        (-> (delete-from :article-source)
            (where [:= :source-id source-id])
            do-execute))
      (finally
        (clear-project-cache (source-id->project-id source-id))))))

(defn fail-source-import
  "Update database in response to an error during the import process
  for a project-source."
  [source-id]
  (alter-source-meta
   source-id #(assoc % :importing-articles? :error))
  (delete-source-articles source-id))

(defn update-project-articles-enabled
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
          (where [:and
                  [:= :project-id project-id]
                  [:exists
                   (-> (select :*)
                       (from [:article-source :ars])
                       (left-join [:project-source :ps]
                                  [:= :ars.source-id :ps.source-id])
                       (where [:and
                               [:= :ps.project-id project-id]
                               [:= :article.article-id :ars.article-id]
                               [:= :ps.enabled true]]))]])
          do-execute)
      ;; check the article_flags table as the ultimate truth
      ;; for the enabled setting
      (-> (sqlh/update :article)
          (sset {:enabled false})
          (where [:and
                  [:= :project-id project-id]
                  [:exists
                   (-> (select :*)
                       (from [:article-flag :af])
                       (where [:and
                               [:= :af.article-id :article.article-id]
                               [:= :af.disable true]]))]])
          do-execute))
    (finally
      (clear-project-cache project-id))))
;;
(s/fdef update-project-articles-enabled
        :args (s/cat :project-id int?))

(defn delete-source
  "Given a source-id, delete it and remove the articles associated with
  it from the database.  Warning: This fn doesn't care if there are
  labels associated with an article"
  [source-id]
  (let [project-id (source-id->project-id source-id)]
    (alter-source-meta
     source-id #(assoc % :deleting? true))
    (try (with-transaction
           ;; delete articles that aren't contained in another source
           (delete-source-articles source-id)
           ;; delete entries for project source
           (-> (delete-from :project-source)
               (where [:= :source-id source-id])
               do-execute)
           ;; update the article enabled flags
           (update-project-articles-enabled project-id)
           true)
         (catch Throwable e
           (log/info "Caught exception in sysrev.db.sources/delete-source: "
                     (.getMessage e))
           (alter-source-meta
            source-id #(assoc % :deleting? false))
           false))))
;;
(s/fdef delete-source
        :args (s/cat :source-id int?))

(defn source-articles-with-labels
  "Given a source-id, return the amount of articles that have labels"
  [source-id]
  (let [project-id (source-id->project-id source-id)
        overall-id (project/project-overall-label-id project-id)]
    (-> (select :%count.%distinct.al.article-id)
        (from [:article-source :asrc])
        (join [:article :a] [:= :a.article-id :asrc.article-id]
              [:article-label :al] [:= :al.article-id :a.article-id])
        (where [:and
                [:= :a.project-id project-id]
                [:= :asrc.source-id source-id]
                [:= :al.label-id overall-id]
                [:!= :al.answer nil]
                [:!= :al.answer (to-jsonb nil)]
                [:!= :al.confirm-time nil]])
        do-query first :count)))
;;
(s/fdef source-articles-with-labels
        :args (s/cat :source-id int?)
        :ret (s/nilable int?))

(defn source-unique-articles-count
  "Return map of {source-id -> unique-article-count} for project sources"
  [project-id]
  (with-project-cache
    project-id [:sources :unique-counts]
    (let [asources (-> (select :asrc.*)
                       (from [:project-source :psrc])
                       (join [:article-source :asrc]
                             [:= :asrc.source-id :psrc.source-id])
                       (where [:and
                               [:= :psrc.project-id project-id]
                               [:= :psrc.enabled true]])
                       (->> do-query (group-by :article-id)))]
      (-> (select :source-id)
          (from :project-source)
          (where [:and
                  [:= :project-id project-id]
                  [:= :enabled true]])
          (->> do-query
               (pmap (fn [{:keys [source-id]}]
                       {source-id
                        (->> (vals asources)
                             (filter #(and (= 1 (count %))
                                           (= source-id (:source-id (first %)))))
                             count)}))
               (apply merge {}))))))

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

(defn project-source-ids [project-id & {:keys [enabled] :or {enabled nil}}]
  (-> (select :ps.source-id)
      (from [:project-source :ps])
      (where [:and
              [:= :ps.project-id project-id]
              (cond (true? enabled)   [:= :ps.enabled true]
                    (false? enabled)  [:= :ps.enabled false]
                    :else             true)])
      (->> do-query (mapv :source-id))))

(defn project-sources-overlap
  "Given a project-id, determine the overlap of all enabled sources"
  [project-id]
  (with-project-cache
    project-id [:sources :overlap]
    (let [source-ids (project-source-ids project-id)]
      (->> (for [id1 source-ids, id2 source-ids]
             (when (< id1 id2)
               (let [overlap (project-source-overlap project-id id1 id2)]
                 [{:count overlap, :source-id id1, :overlap-source-id id2}
                  {:count overlap, :source-id id2, :overlap-source-id id1}])))
           (apply concat)))))
;;
(s/fdef project-sources-overlap
        :args (s/cat :project-id int?)
        :ret coll?)

(defn project-sources
  "Given a project-id, return the corresponding vectors of
  project-source data or nil if it does not exist"
  [project-id]
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
                      (-> (select :%count.*)
                          (from [:article-source :asrc])
                          (where [:= :asrc.source-id source-id])
                          do-query first :count)
                      labeled-count (source-articles-with-labels source-id)
                      overlap (->> overlap-coll
                                   (filter #(= (:source-id %) source-id))
                                   (mapv #(select-keys % [:overlap-source-id :count])))]
                  (merge psource
                         {:article-count article-count
                          :labeled-article-count labeled-count
                          :overlap overlap
                          :unique-articles-count (get unique-coll source-id)}))))))))
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

(defn add-article-to-source
  "Add article-id to source-id"
  [article-id source-id]
  (-> (sqlh/insert-into :article-source)
      (values [{:article-id article-id
                :source-id source-id}])
      do-execute))

(defn add-articles-to-source
  "Add list of article-id values to source-id"
  [article-ids source-id]
  (when (not-empty article-ids)
    (-> (sqlh/insert-into :article-source)
        (values (mapv (fn [aid] {:article-id aid :source-id source-id})
                      article-ids))
        do-execute)))

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

(defn toggle-source
  "Toggle a source has enabled?"
  [source-id enabled?]
  (with-transaction
    (-> (sqlh/update :project-source)
        (sset {:enabled enabled?})
        (where [:= :source-id source-id])
        do-execute)
    ;; logic for updating enabled in article
    (update-project-articles-enabled (source-id->project-id source-id))))
;;
(s/fdef toggle-source
        :args (s/cat :source-id int?
                     :enabled? boolean?))
