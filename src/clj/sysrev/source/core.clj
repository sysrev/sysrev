(ns sysrev.source.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [delete-from from group having
                                               insert-into join left-join merge-where select
                                               values where]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.article.core :as article]
            [sysrev.db.core :as db :refer
             [do-execute do-query with-project-cache
              with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.file.s3 :as s3-file]
            [sysrev.project.core :as project]
            [sysrev.util :as util :refer [index-by map-values]]))

(defn datapub-opts [{:keys [config]} & {:keys [upload?]}]
  {:auth-token (:sysrev-dev-key config)
   :endpoint (if upload?
               (:datapub-endpoint config)
               (:graphql-endpoint config))})

(defn get-source
  "Get fields for project-source entry matching source-id."
  [source-id]
  (-> (select :*) (from :project-source) (where [:= :source-id source-id]) do-query first))

(defn source-upload-file
  "Get meta map for uploaded file attached to source-id."
  [source-id]
  (-> (get-source source-id) :meta :s3-file))

(defn-spec source-id->project-id (s/nilable int?)
  [source-id int?]
  (q/find-one :project-source {:source-id source-id} :project-id))

(defn-spec create-source int?
  "Create an entry in project-source table."
  [project-id int?, metadata map?]
  (db/with-clear-project-cache project-id
    (q/create :project-source {:project-id project-id :meta metadata :import-date :%now}
              :returning :source-id)))

(defmulti re-import (fn [_sr-context _project-id source]
                      (-> source :meta :source)))

(defmethod re-import :default [_ _ _]
  (throw (Exception. "invalid source-type")))

(defn-spec ^:private set-source-meta int?
  [source-id int?, metadata map?]
  (db/with-clear-project-cache (source-id->project-id source-id)
    (q/modify :project-source {:source-id source-id} {:meta metadata})))

(defn-spec alter-source-meta int?
  "Replaces the meta field for source-id with the result of applying
  function f to the existing value."
  [source-id int?, f ifn?]
  (db/with-transaction
    (let [meta (q/find-one :project-source {:source-id source-id} :meta)]
      (set-source-meta source-id (f meta)))))

(defn- delete-source-articles
  "Deletes all article-source entries for source-id, and their associated
  article entries unless contained in another source."
  [source-id]
  (db/with-clear-project-cache (source-id->project-id source-id)
    (let [project-id (source-id->project-id source-id)
          asources (-> (select :*)
                       (from [:article-source :as])
                       (where [:= :as.article-id :a.article-id]))]
      (-> (delete-from [:article :a])
          (where [:and
                  [:= :a.project-id project-id]
                  [:exists (-> asources (merge-where [:= :as.source-id source-id]))]
                  [:not [:exists (-> asources (merge-where [:!= :as.source-id source-id]))]]])
          do-execute)
      (q/delete :article-source {:source-id source-id}))))

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
      (->> do-query (index-by :article-id) (map-values :enabled))))

(defn-spec update-project-articles-enabled nil?
  "Update the enabled fields of articles associated with project-id."
  [project-id int?]
  (db/with-clear-project-cache project-id
    (let [;; get id for all articles in project
          article-ids (project/project-article-ids project-id)
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
      (article/modify-articles-by-id update-enable-ids {:enabled true})
      (article/modify-articles-by-id update-disable-ids {:enabled false})
      nil)))

(defn-spec delete-source boolean?
  "Delete a project source and disable/delete articles as needed. Any
  articles which are only linked to this source will be deleted."
  [source-id int?]
  (let [project-id (source-id->project-id source-id)]
    ;; Update status first so client can see this is in progress
    (alter-source-meta source-id #(assoc % :deleting? true))
    (try (with-transaction
           ;; delete articles that aren't contained in another source
           (delete-source-articles source-id)
           ;; delete entries for project source
           (q/delete :project-source {:source-id source-id})
           ;; update the article enabled flags
           (update-project-articles-enabled project-id)
           true)
         (catch Throwable e
           (log/info "Caught exception in sysrev.source.core/delete-source:" (.getMessage e))
           (alter-source-meta source-id #(assoc % :deleting? false))
           false))))

(defn-spec source-articles-with-labels int?
  "Return count of labeled articles within source-id."
  [source-id int?]
  (if-let [project-id (source-id->project-id source-id)]
    (let [overall-id (project/project-overall-label-id project-id)]
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
          (->> (map (fn [{:keys [source-id]}]
                      {source-id (->> (vals asources)
                                      (filter #(and (= 1 (count %))
                                                    (= source-id (:source-id (first %)))))
                                      count)}))
               (apply merge {}))))))

(defn-spec project-source-overlap int?
  "Return count of overlapping articles between two sources, ignoring
  entries from disabled sources."
  [project-id int?, source-id-1 int?, source-id-2 int?]
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

(defn project-source-ids [project-id & {:keys [enabled] :or {enabled nil}}]
  (-> (select :ps.source-id)
      (from [:project-source :ps])
      (where [:and
              [:= :ps.project-id project-id]
              (cond (true? enabled)   [:= :ps.enabled true]
                    (false? enabled)  [:= :ps.enabled false]
                    :else             true)])
      (->> do-query (mapv :source-id))))

(defn-spec project-sources-overlap (s/nilable (s/coll-of map?))
  "Return sequence of maps for each pair of enabled sources in project,
  containing count of overlapping articles for the pair."
  [project-id int?]
  (with-project-cache project-id [:sources :overlap]
    (let [source-ids (project-source-ids project-id :enabled true)]
      (->> (for [id1 source-ids, id2 source-ids]
             (when (< id1 id2)
               (let [overlap (project-source-overlap project-id id1 id2)]
                 [{:count overlap, :source-id id1, :overlap-source-id id2}
                  {:count overlap, :source-id id2, :overlap-source-id id1}])))
           (apply concat)))))

(defn-spec project-sources-basic vector?
  "Returns vector of source information maps for project-id, with just
  basic information and excluding more expensive queries."
  [project-id int?]
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

(defn-spec project-sources vector?
  "Returns vector of source information maps for project-id."
  [sr-context map? project-id int?]
  (with-transaction
    (let [overlap-coll (project-sources-overlap project-id)
          unique-coll (source-unique-articles-count project-id)]
      (-> (select :dataset-id :source-id :project-id :meta :enabled :date-created
                  :import-date :check-new-results :import-new-results :notes
                  :new-articles-available)
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
                               :unique-articles-count (get unique-coll source-id)})))
               ;; process any cursors that exist
               (mapv #(let [cursors (get-in % [:meta :cursors])]
                        (assoc-in % [:meta :cursors]
                                  (mapv (fn [_] (mapv keyword _)) cursors)))))))))

(defn-spec source-has-labeled-articles? boolean?
  [source-id int?]
  (pos? (source-articles-with-labels source-id)))

(defn add-articles-to-source
  "Create article-source entries linking article-ids to source-id."
  [article-ids source-id]
  (when (seq article-ids)
    (-> (insert-into :article-source)
        (values (for [id article-ids] {:article-id id :source-id source-id}))
        do-execute)))

(defn-spec source-exists? boolean?
  [source-id int?]
  (= source-id (-> (select :source-id)
                   (from [:project-source :ps])
                   (where [:= :ps.source-id source-id])
                   do-query first :source-id)))

(defn-spec toggle-source boolean?
  "Set enabled status for source-id."
  [source-id int?, enabled boolean?]
  (with-transaction
    (q/modify :project-source {:source-id source-id} {:enabled enabled})
    (update-project-articles-enabled (source-id->project-id source-id))
    enabled))

(defn-spec update-source some?
  "Set enabled status for source-id."
  [project-id int?, source-id int?, check-new-results? boolean?, import-new-results? boolean?, notes string?]
  (db/with-clear-project-cache project-id
    (with-transaction
      (q/modify :project-source
                {:source-id source-id}
                {:check-new-results check-new-results?
                 :import-new-results import-new-results?
                 :notes notes}))))

(defn-spec set-import-date some?
  "Set enabled status for source-id."
  [source-id int?]
  (with-transaction
    (q/modify :project-source {:source-id source-id} {:import-date :%now})))

(defn-spec set-new-articles-available some?
  "Set enabled status for source-id."
  [project-id int?, source-id int?, new-articles-count int?]
  (db/with-clear-project-cache project-id
    (with-transaction
      (q/modify :project-source {:source-id source-id} {:new-articles-available new-articles-count}))))

;; FIX: handle duplicate file uploads, don't create new copy
(defn save-import-file [source-id filename file]
  (let [file-hash (s3-file/save-file file :import)
        file-meta {:filename filename :key file-hash}]
    (alter-source-meta source-id #(assoc % :s3-file file-meta))))
