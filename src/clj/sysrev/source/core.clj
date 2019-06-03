(ns sysrev.source.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-jsonb
              clear-project-cache with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as p]
            [sysrev.article.core :as a]
            [sysrev.filestore :as fstore]
            [sysrev.shared.util :as sutil :refer [in? map-values ->map-with-key]]))

(defn get-source
  "Get fields for project-source entry matching source-id."
  [source-id]
  (-> (select :*) (from :project-source) (where [:= :source-id source-id]) do-query first))

(defn source-upload-file
  "Get meta map for uploaded file attached to source-id."
  [source-id]
  (-> (get-source source-id) :meta :s3-file))

(defn source-id->project-id [source-id]
  (-> (select :project-id)
      (from :project-source)
      (where [:= :source-id source-id])
      do-query first :project-id))
;;;
(s/fdef source-id->project-id
  :args (s/cat :source-id int?)
  :ret (s/nilable int?))

(defn create-source
  "Create an entry in project-source table."
  [project-id metadata]
  (try (-> (insert-into :project-source)
           (values [{:project-id project-id, :meta metadata}])
           (returning :source-id)
           do-query first :source-id)
       (finally (clear-project-cache project-id))))
;;;
(s/fdef create-source
  :args (s/cat :project-id int? :metadata map?)
  :ret int?)

(defmulti make-source-meta (fn [source-type values] source-type))

(defmethod make-source-meta :default [source-type values]
  (throw (Exception. "invalid source-type")))

(defn- set-source-meta [source-id metadata]
  (try (-> (sqlh/update :project-source)
           (sset {:meta metadata})
           (where [:= :source-id source-id])
           do-execute)
       (finally (clear-project-cache (source-id->project-id source-id)))))
;;;
(s/fdef set-source-meta
  :args (s/cat :source-id int? :metadata map?))

(defn alter-source-meta
  "Replaces the meta field for source-id with the result of applying
  function f to the existing value."
  [source-id f]
  (when-let [meta (-> (select :meta)
                      (from :project-source)
                      (where [:= :source-id source-id])
                      do-query first :meta)]
    (set-source-meta source-id (f meta))))
;;;
(s/fdef alter-source-meta
  :args (s/cat :source-id int? :f ifn?))

(defn- delete-source-articles
  "Deletes all article-source entries for source-id, and their associated
  article entries unless contained in another source."
  [source-id]
  (with-transaction
    (try (let [project-id (source-id->project-id source-id)
               asources (-> (select :*)
                            (from [:article-source :as])
                            (where [:= :as.article-id :a.article-id]))]
           (-> (delete-from [:article :a])
               (where [:and
                       [:= :a.project-id project-id]
                       [:exists (-> asources (merge-where [:= :as.source-id source-id]))]
                       [:not [:exists (-> asources (merge-where [:!= :as.source-id source-id]))]]])
               do-execute)
           (q/delete-by-id :article-source :source-id source-id))
         (finally (clear-project-cache (source-id->project-id source-id))))))

(defn fail-source-import
  "Update database in response to an error during the import process
  for a project-source."
  [source-id]
  (alter-source-meta source-id #(assoc % :importing-articles? :error))
  (delete-source-articles source-id))

(defn project-article-sources-map
  "Returns map of {article-id -> [source-id]} representing the sources each
  article is contained in."
  [project-id & {:keys [enabled] :or {enabled nil}}]
  (-> (select :as.article-id :as.source-id :a.enabled)
      (from [:project-source :ps])
      (join [:article-source :as] [:= :as.source-id :ps.source-id]
            [:article :a]         [:= :a.article-id :as.article-id])
      (where [:and
              [:= :ps.project-id project-id]
              (cond (true? enabled)   [:= :ps.enabled true]
                    (false? enabled)  [:= :ps.enabled false]
                    :else             true)])
      (->> do-query
           (group-by :article-id)
           (map-values #(mapv :source-id %)))))

(defn- project-articles-disable-flagged
  "Returns set of ids for project articles disabled by flag."
  [project-id]
  (-> (select :af.article-id)
      (from [:article :a])
      (join [:article-flag :af]
            [:= :af.article-id :a.article-id])
      (where [:and
              [:= :a.project-id project-id]
              [:= :af.disable true]])
      (->> do-query (map :article-id) set)))

(defn- project-articles-enabled-state
  "Returns map of {article-id -> enabled} representing current values of
  cached \"enabled\" state for articles in project."
  [project-id]
  (-> (select :article-id :enabled)
      (from :article)
      (where [:= :project-id project-id])
      (->> do-query (->map-with-key :article-id) (map-values :enabled))))

(defn update-project-articles-enabled
  "Update the enabled fields of articles associated with project-id."
  [project-id]
  (try
    (with-transaction
      (let [ ;; get id for all articles in project
            article-ids (p/project-article-ids project-id)
            ;; get list of enabled sources for each article
            a-sources (project-article-sources-map project-id :enabled true)
            ;; get current state to check against new computed value
            a-enabled (project-articles-enabled-state project-id)
            ;; get flag entries that force-disable specific articles by id
            a-flagged (project-articles-disable-flagged project-id)
            ;; list of articles that should be enabled
            enabled-ids (->> article-ids
                             (filter #(and (not-empty (get a-sources %))
                                           (not (contains? a-flagged %)))))
            ;; list of articles that should be disabled
            disabled-ids (->> article-ids
                              (filter #(or (empty? (get a-sources %))
                                           (contains? a-flagged %))))
            ;; list of articles we need to change to enabled
            update-enable-ids (->> enabled-ids (filter #(false? (get a-enabled %))))
            ;; list of articles we need to change to disabled
            update-disable-ids (->> disabled-ids (filter #(true? (get a-enabled %))))]
        ;; apply db updates for changed articles
        (a/modify-articles-by-id update-enable-ids {:enabled true})
        (a/modify-articles-by-id update-disable-ids {:enabled false})
        nil))
    (finally
      (clear-project-cache project-id))))
;;;
(s/fdef update-project-articles-enabled
  :args (s/cat :project-id int?))

(defn delete-source
  "Delete a project source and disable/delete articles as needed. Any
  articles which are only linked to this source will be deleted."
  [source-id]
  (let [project-id (source-id->project-id source-id)]
    ;; Update status first so client can see this is in progress
    (alter-source-meta source-id #(assoc % :deleting? true))
    (try (with-transaction
           ;; delete articles that aren't contained in another source
           (delete-source-articles source-id)
           ;; delete entries for project source
           (q/delete-by-id :project-source :source-id source-id)
           ;; update the article enabled flags
           (update-project-articles-enabled project-id)
           true)
         (catch Throwable e
           (log/info "Caught exception in sysrev.source.core/delete-source:" (.getMessage e))
           (alter-source-meta source-id #(assoc % :deleting? false))
           false))))
;;;
(s/fdef delete-source
  :args (s/cat :source-id int?))

(defn source-articles-with-labels
  "Return count of labeled articles within source-id."
  [source-id]
  (if-let [project-id (source-id->project-id source-id)]
    (let [overall-id (p/project-overall-label-id project-id)]
      (-> (select :%count.%distinct.al.article-id)
          (from [:article-source :asrc])
          (join [:article :a] [:= :a.article-id :asrc.article-id]
                [:article-label :al] [:= :al.article-id :a.article-id])
          (where [:and
                  [:= :a.project-id project-id]
                  [:= :asrc.source-id source-id]
                  [:= :al.label-id overall-id]])
          (q/filter-valid-article-label true)
          do-query first :count))
    0))
;;;
(s/fdef source-articles-with-labels
  :args (s/cat :source-id int?)
  :ret (s/nilable int?))

(defn source-unique-articles-count
  "Return map of {source-id -> unique-article-count} for project sources"
  [project-id]
  (with-project-cache project-id [:sources :unique-counts]
    (let [asources (-> (select :as.*)
                       (from [:project-source :ps])
                       (join [:article-source :as] [:= :as.source-id :ps.source-id])
                       (where [:and [:= :ps.project-id project-id] [:= :ps.enabled true]])
                       (->> do-query (group-by :article-id)))]
      (-> (select :source-id)
          (from :project-source)
          (where [:and [:= :project-id project-id] [:= :enabled true]])
          do-query
          (->> (pmap (fn [{:keys [source-id]}]
                       {source-id (->> (vals asources)
                                       (filter #(and (= 1 (count %))
                                                     (= source-id (:source-id (first %)))))
                                       count)}))
               (apply merge {}))))))

(defn project-source-overlap
  "Return count of overlapping articles between two sources, ignoring
  entries from disabled sources."
  [project-id source-id-1 source-id-2]
  (count (-> (select :%count.*)
             (from [:article-source :as])
             (left-join [:project-source :ps] [:= :as.source-id :ps.source-id])
             (where [:and
                     [:= :ps.project-id project-id]
                     [:= :ps.enabled true]
                     [:or
                      [:= :ps.source-id source-id-1]
                      [:= :ps.source-id source-id-2]]])
             (group :as.article-id)
             (having [:> :%count.* 1])
             do-query)))
;;;
(s/fdef project-source-overlap
  :args (s/cat :project-id int? :source-id-1 int? :source-id-2 int?)
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
  "Return sequence of maps for each pair of enabled sources in project,
  containing count of overlapping articles for the pair."
  [project-id]
  (with-project-cache project-id [:sources :overlap]
    (let [source-ids (project-source-ids project-id :enabled true)]
      (->> (for [id1 source-ids, id2 source-ids]
             (when (< id1 id2)
               (let [overlap (project-source-overlap project-id id1 id2)]
                 [{:count overlap, :source-id id1, :overlap-source-id id2}
                  {:count overlap, :source-id id2, :overlap-source-id id1}])))
           (apply concat)))))
;;;
(s/fdef project-sources-overlap
  :args (s/cat :project-id int?)
  :ret (s/coll-of map?))

(defn project-sources-basic
  "Returns vector of source information maps for project-id, with just
  basic information and excluding more expensive queries."
  [project-id]
  (with-project-cache project-id [:sources :basic-maps]
    (with-transaction
      (-> (select :source-id :project-id :meta :enabled :date-created)
          (from [:project-source :ps])
          (where [:= :ps.project-id project-id])
          (->> do-query
               (mapv (fn [{:keys [source-id] :as psource}]
                       (merge psource
                              {:article-count (-> (select :%count.*)
                                                  (from [:article-source :asrc])
                                                  (where [:= :asrc.source-id source-id])
                                                  do-query first :count)}))))))))
;;;
(s/fdef project-sources-basic
  :args (s/cat :project-id int?)
  :ret vector?)

(defn project-sources
  "Returns vector of source information maps for project-id."
  [project-id]
  (with-transaction
    (let [overlap-coll (project-sources-overlap project-id)
          unique-coll (source-unique-articles-count project-id)]
      (-> (select :source-id :project-id :meta :enabled :date-created)
          (from [:project-source :ps])
          (where [:= :ps.project-id project-id])
          (->> do-query
               (mapv (fn [{:keys [source-id] :as psource}]
                       (merge psource
                              {:article-count (-> (select :%count.*)
                                                  (from [:article-source :asrc])
                                                  (where [:= :asrc.source-id source-id])
                                                  do-query first :count)
                               :labeled-article-count (source-articles-with-labels source-id)
                               :overlap (->> overlap-coll
                                             (filter #(= (:source-id %) source-id))
                                             (mapv #(select-keys % [:overlap-source-id :count])))
                               :unique-articles-count (get unique-coll source-id)}))))))))
;;;
(s/fdef project-sources
  :args (s/cat :project-id int?)
  :ret vector?)

(defn source-has-labeled-articles? [source-id]
  (pos? (source-articles-with-labels source-id)))
;;;
(s/fdef source-has-labeled-articles?
  :args (s/cat :source-id int?)
  :ret boolean?)

(defn add-article-to-source
  "Create an article-source entry linking article-id to source-id."
  [article-id source-id]
  (-> (sqlh/insert-into :article-source)
      (values [{:article-id article-id :source-id source-id}])
      do-execute))

(defn add-articles-to-source
  "Create article-source entries linking article-ids to source-id."
  [article-ids source-id]
  (when (seq article-ids)
    (-> (sqlh/insert-into :article-source)
        (values (for [id article-ids] {:article-id id :source-id source-id}))
        do-execute)))

(defn source-exists? [source-id]
  (= source-id (-> (select :source-id)
                   (from [:project-source :ps])
                   (where [:= :ps.source-id source-id])
                   do-query first :source-id)))
;;;
(s/fdef source-exists?
  :args (s/cat :source-id int?)
  :ret boolean?)

(defn toggle-source
  "Set enabled status for source-id."
  [source-id enabled?]
  (with-transaction
    (-> (sqlh/update :project-source)
        (sset {:enabled enabled?})
        (where [:= :source-id source-id])
        do-execute)
    ;; logic for updating enabled in article
    (update-project-articles-enabled (source-id->project-id source-id))))
;;;
(s/fdef toggle-source
  :args (s/cat :source-id int? :enabled? boolean?))

;; FIX: handle duplicate file uploads, don't create new copy
(defn save-import-file [source-id filename file]
  (let [file-hash (fstore/save-file file :import)
        file-meta {:filename filename :key file-hash} ]
    (alter-source-meta source-id #(assoc % :s3-file file-meta))))
