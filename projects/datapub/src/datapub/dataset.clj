(ns datapub.dataset
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datapub.auth :as auth]
            [medley.core :as medley]
            [datapub.dataset.entity :as entity]
            [datapub.util :as util :refer [ensure-sysrev-dev execute! execute-one! plan with-tx-context]]
            [sysrev.lacinia.interface :as sl])
  (:import (org.postgresql.jdbc PgArray)))

(let [cols {:created :created
            :description :description
            :name :name
            :public :public}
      cols-inv (sl/invert cols)]

  (defn resolve-dataset [context {:keys [id]} _]
    (when-let [int-id (sl/parse-int-id id)]
      (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
        (when-not (or (util/public-dataset? context int-id)
                      (auth/can-read-dataset? context int-id))
                  (ensure-sysrev-dev context))
        (let [ks (conj (sl/current-selection-names context) :id)
              select (keep cols ks)]
          (-> (when (seq select)
                (sl/remap-keys
                 cols-inv
                 (execute-one!
                  context
                  {:select select
                   :from :dataset
                   :where [:= :id int-id]})))
              (assoc :id id))))))

  (defn create-dataset! [context {:keys [input]} _]
    (ensure-sysrev-dev
     context
     (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
       (let [{:dataset/keys [id]}
             #__ (execute-one!
                  context
                  {:insert-into :dataset
                   :values [(medley/map-keys cols input)]
                   :returning :id})]
         (when id
           (resolve-dataset context {:id (str id)} nil))))))

  (defn update-dataset! [context {{:keys [id] :as input} :input} _]
    (when-let [int-id (sl/parse-int-id id)]
      (ensure-sysrev-dev
       context
       (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
         (let [set (medley/map-keys cols (dissoc input :id))
               {:dataset/keys [id]}
               #__ (if (empty? set)
                     {:dataset/id id}
                     (execute-one!
                      context
                      {:update :dataset
                       :set set
                       :where [:= :id int-id]
                       :returning :id}))]
           (when id
             (resolve-dataset context {:id (str id)} nil))))))))

(defn internal-path-vec
  "Returns a path vector for use in postgres. Numbers are turned into strings,
  and the :* keyword is replaced with the string \":datapub/*\"."
  [path-seq]
  (mapv
   #(cond (= :* %) ":datapub/*"
          (number? %) (str %)
          :else %)
   path-seq))

(defn external-path-vec
  "Changes \":datapub/*\" back into :*. See `internal-path-vec`."
  [path-seq]
  (mapv #(if (= ":datapub/*" %) :* %) path-seq))

(defn resolve-Dataset#indices [context _ {:keys [id]}]
  (when-let [int-id (sl/parse-int-id id)]
    (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
      (when-not (util/public-dataset? context int-id)
        (ensure-sysrev-dev context))
      (->> (execute!
            context
            {:select [:path :type-name]
             :from :dataset-index-spec
             :join [:index-spec [:= :id :index-spec-id]]
             :where [:= :dataset-id int-id]})
           (map
            (fn [{:index-spec/keys [path type-name]}]
              {:path (->> ^PgArray path
                          .getArray
                          internal-path-vec
                          pr-str)
               :type (keyword (str/upper-case type-name))}))))))

(defn list-datasets [context args _]
  (util/connection-helper
   context args
   {:count-f
    (fn [{:keys [context]}]
      (:count
       (execute-one! context {:select :%count.id :from :dataset})))
    :edges-f
    (fn [{:keys [context cursor limit]}]
      (->> {:select :id
            :from :dataset
            :limit limit
            :where [:> :id cursor]
            :order-by [:id]}
           (execute! context)
           (map (fn [{:dataset/keys [id]}]
                  {:cursor (str id) :node {:id (str id)}}))
           (split-at (dec limit))))}))

(defn resolve-ListDatasetsEdge#node [context _ {:keys [node]}]
  (resolve-dataset context node nil))

(defn resolve-Dataset#entities
  [context {:keys [externalId groupingId] :as args} {:keys [id]}]
  (when-let [int-id (sl/parse-int-id id)]
    (let [where [:and
                 [:= :dataset-id int-id]
                 (when externalId
                   [:= :external-id externalId])
                 (when groupingId
                   [:= :grouping-id groupingId])]]
      (util/connection-helper
       context args
       {:count-f
        (fn [{:keys [context]}]
          (->> {:select :%count.id
                :from :entity
                :where where}
               (execute-one! context)
               :count))
        :edges-f
        (fn [{:keys [context cursor limit]}]
          (->> {:select :id
                :from :entity
                :limit limit
                :where [:and [:> :id cursor] where]
                :order-by [:id]}
               (execute! context)
               (map (fn [{:entity/keys [id]}]
                      {:cursor (str id) :node {:id (str id)}}))
               (split-at (dec limit))))}))))

(defn resolve-DatasetEntitiesEdge#node [context _ {:keys [node]}]
  (entity/resolve-dataset-entity context node nil))

(defn valid-index-path? [path-vec]
  (and (vector? path-vec)
       (seq path-vec)
       (every? #(or (string? %)
                    (nat-int? %)
                    (= :* %))
               path-vec)))

(defn get-index-spec [context path-vec type]
  (let [path-vec (internal-path-vec path-vec)]
    (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
      (execute-one!
       context
       {:select :*
        :from :index-spec
        :where
        [:and [:= :path [:array path-vec]] [:= :type-name type]]}))))

(defn get-or-create-index-spec! [context path-vec type]
  (or (get-index-spec context path-vec type)
      (let [path-vec (internal-path-vec path-vec)]
        (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
          (execute-one!
           context
           {:insert-into :index-spec
            :values [{:path [:array path-vec] :type-name type}]
            :on-conflict []
            :do-nothing []})
          (get-index-spec context path-vec type)))))

(defn resolve-dataset-index [context {:keys [datasetId path type]} _]
  (when-let [dataset-int-id (sl/parse-int-id datasetId)]
    (let [path-vec (try (edn/read-string path) (catch Exception _))]
      (when (valid-index-path? path-vec)
        (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
          (when-not (util/public-dataset? context dataset-int-id)
            (ensure-sysrev-dev context))
          (some->
           (get-index-spec context path-vec (-> type name str/lower-case))
           :index-spec/id
           (as-> $
               (execute-one!
                context
                {:select true
                 :from :dataset-index-spec
                 :where [:and
                         [:= :dataset-id dataset-int-id]
                         [:= :index-spec-id $]]})
             (when $
               {:path (pr-str path-vec)
                :type type}))))))))

(defn create-dataset-index! [context {{:keys [datasetId path type]} :input} _]
  (ensure-sysrev-dev
   context
   (let [dataset-int-id (sl/parse-int-id datasetId)
         path-vec (try (edn/read-string path) (catch Exception _))]
     (if-not (valid-index-path? path-vec)
       (resolve/resolve-as nil {:message "Invalid index path."
                                :path path})
       (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
         (if (or (not dataset-int-id)
                 (empty? (execute-one! context {:select :id
                                                :from :dataset
                                                :where [:= :id dataset-int-id]})))
           (resolve/resolve-as nil {:message "There is no dataset with that id."
                                    :datasetId datasetId})
           (let [index-spec-id (->> type name str/lower-case
                                    (get-or-create-index-spec! context path-vec)
                                    :index-spec/id)]
             (execute-one!
              context
              {:insert-into :dataset-index-spec
               :on-conflict []
               :do-nothing []
               :values
               [{:dataset-id dataset-int-id
                 :index-spec-id index-spec-id}]})
             (resolve-dataset-index
              context
              {:datasetId datasetId
               :path (pr-str (-> path-vec internal-path-vec external-path-vec))
               :type type}
              nil))))))))

(defn string-query->sqlmap [_ {:keys [eq ignoreCase path]}]
  (let [path-vec (internal-path-vec (edn/read-string path))
        maybe-lower #(if ignoreCase [:lower %] %)]
    [:in
     (maybe-lower (pr-str eq))
     {:select
      [[(maybe-lower
         [:unnest [:cast
                   [:get_in_jsonb :indexed-data [:array path-vec]]
                   [:raw "text[]"]]])]]}]))

(defn text-query->sqlmap [index-spec-ids {:keys [paths search useEveryIndex]}]
  (if (if useEveryIndex paths (not paths))
    (throw (RuntimeException. "Either paths or useEveryIndex must be specified, but not both."))
    (some->> (if useEveryIndex
               (:use-every-index-text index-spec-ids)
               (mapcat index-spec-ids paths))
             (map #(vector (keyword "@@")
                           (keyword (str "index-" %))
                           [:websearch_to_tsquery
                            [:raw "'english'::regconfig"]
                            search]))
             seq
             (apply vector :or))))

(defn search-dataset-query->sqlmap
  "Returns a HoneySQL sqlmap for a SearchDatasetQueryInput or one
  of its children.

  index-spec-ids should be a map of path string -> vector of index-spec-ids."
  [index-spec-ids {:keys [query string text type]}]
  (apply
   vector
   (case type
     :AND :and
     :OR :or)
   (concat
    (map (partial search-dataset-query->sqlmap index-spec-ids) query)
    (map (partial string-query->sqlmap index-spec-ids) string)
    (map (partial text-query->sqlmap index-spec-ids) text))))

(defn get-search-query-paths
  "Returns a seq of all of the index paths in a SearchDatasetQueryInput or one
  of its children.

  Values are conj'd to the paths argument. If paths is nil, it will be
  initialized to #{}."
  [query & [paths]]
  (let [{:keys [path string text useEveryIndex]} query
        paths (as-> (or paths #{}) $
                (if path (conj $ path) $)
                (if useEveryIndex (conj $ :use-every-index-text) $)
                (into $ (:paths query)))]
    (reduce #(get-search-query-paths %2 %) paths (concat (:query query) string text))))

(defn get-index-spec-ids-for-path [context dataset-id path]
  (if (= :use-every-index-text path)
    (->> {:select :index-spec-id
          :from :dataset-index-spec
          :join [:index-spec [:= :id :index-spec-id]]
          :where [:and
                  [:= :dataset-id dataset-id]
                  [:= "text" :index-spec.type-name]]}
         (execute! context)
         (map :dataset-index-spec/index-spec-id))
    [(let [path-vec (try (edn/read-string path) (catch Exception _))]
       (or (when (valid-index-path? path-vec)
             (:index-spec/id (get-index-spec context path-vec "text")))
           (throw (ex-info "Index does not exist." {:path path}))))]))

(defn search-dataset-query->select
  [context {:keys [datasetId query uniqueExternalIds uniqueGroupingIds]}]
  (let [dataset-int-id (parse-long datasetId)
        index-spec-ids (reduce
                        #(assoc % %2 (get-index-spec-ids-for-path context dataset-int-id %2))
                        {}
                        (get-search-query-paths query))
        where (search-dataset-query->sqlmap index-spec-ids query)
        q {:select :entity-id
           :from (keyword (str "indexed-entity-" dataset-int-id))
           :where where}]
    (if-not (or uniqueExternalIds uniqueGroupingIds)
      q
      (assoc
       q
       :join [:entity [:= :entity-id :entity.id]]
       :where [:and
               where
               [:in [:concat [:coalesce :external-created :created] :created]
                {:select [[[:max [:concat [:coalesce :external-created :created] :created]]]]
                 :from [[:entity :e]]
                 :where [:and
                         [:= :e.dataset-id :entity.dataset-id]
                         (if uniqueExternalIds
                           [:= :e.external-id :entity.external-id]
                           [:= :e.grouping-id :entity.grouping-id])]}]]))))

(def search-dataset-query-keys [:query :string :text])

(defn fail-subscription [source-stream resolver-errors]
  (source-stream (resolve/resolve-as nil resolver-errors))
  (constantly nil))

(defn search-dataset-subscription
  [context
   {{:keys [datasetId query uniqueExternalIds uniqueGroupingIds] :as input} :input}
   source-stream]
  (let [dataset-int-id (sl/parse-int-id datasetId)]
    (cond
      (and uniqueExternalIds uniqueGroupingIds)
      (fail-subscription
       source-stream
       {:message "uniqueExternalIds and uniqueGroupingIds cannot both be true."})

      (empty? (select-keys query search-dataset-query-keys))
      (fail-subscription
       source-stream
       {:message (str "At least one of these keys must be set: "
                      (str/join search-dataset-query-keys))
        :query query})

      (not (and dataset-int-id
                (or (util/sysrev-dev? context)
                    (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
                      (util/public-dataset? context dataset-int-id)))))
      (fail-subscription
       source-stream
       {:datasetId datasetId
        :message "You are not authorized to access entities in that dataset."})

      :else
      (let [fut (future
                  (try
                    (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
                      (let [q (search-dataset-query->select context input)]
                        (when (some identity (rest q))
                          (reduce
                           (fn [_ row]
                             (source-stream
                              (entity/resolve-dataset-entity context {:id (str (:entity_id row))} nil)))
                           nil
                           (plan context q))))
                      (source-stream nil))
                    (catch Exception e
                      (prn e))))]
        (fn [] (future-cancel fut))))))
