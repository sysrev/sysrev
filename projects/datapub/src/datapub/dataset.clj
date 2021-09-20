(ns datapub.dataset
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.walmartlabs.lacinia.constants :as constants]
            [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [com.walmartlabs.lacinia.selection :as selection]
            [datapub.file :as file]
            [datapub.postgres :as pg]
            [hasch.core :as hasch]
            [medley.core :as me]
            [next.jdbc :as jdbc]
            [sysrev.pdf-read.interface :as pdf-read])
  (:import (java.io IOException)
           (java.nio.file Files StandardCopyOption)
           (java.security MessageDigest)
           (java.util Base64)
           (org.apache.commons.io IOUtils)
           (org.postgresql.util PGobject)))

(defn jsonb-pgobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string x))))

(defn sysrev-dev? [context]
  (let [auth (-> context :request :headers (get "authorization")
                 (or (-> context
                         :com.walmartlabs.lacinia/connection-params
                         :authorization)))
        sysrev-dev-key (-> context :pedestal :config :sysrev-dev-key)]
    (and sysrev-dev-key (= auth (str "Bearer " sysrev-dev-key)))))

(defmacro ensure-sysrev-dev [context & body]
  `(if (sysrev-dev? ~context)
     (do ~@body)
     (throw (RuntimeException. "Unauthorized."))))

(defn execute! [context sqlmap]
  (pg/execute! (:tx context) sqlmap))

(defn execute-one! [context sqlmap]
  (pg/execute-one! (:tx context) sqlmap))

(defn plan [context sqlmap]
  (pg/plan (:tx context) sqlmap))

(defn current-selection-names [context]
  (-> context
      executor/selection
      (or (get-in context [constants/parsed-query-key :selections 0]))
      selection/selections
      (->> (map selection/field-name))
      set))

(defn denamespace-keys [map-or-seq]
  (cond (map? map-or-seq) (me/map-keys (comp keyword name) map-or-seq)
        (sequential? map-or-seq) (map denamespace-keys map-or-seq)))

(defn public-dataset? [context id]
  (-> context
      (execute-one! {:select :* :from :dataset :where [:= :id id]})
      :dataset/public
      boolean))

(defn context-memos [context]
  {:public-dataset? (memoize (partial public-dataset? context))})

(defn call-memo [context kw & args]
  (apply (get-in context [:memos kw]) args))

(defmacro with-tx-context
  "Either use an existing :tx in the context, or create a new transaction
  and assign it to :tx in the context."
  [[name-sym context] & body]
  `(let [context# ~context]
     (if-let [tx# (:tx context#)]
       (let [~name-sym context#] ~@body)
       (jdbc/with-transaction [tx# (get-in context# [:pedestal :postgres :datasource])
                               {:isolation :serializable}]
         (let [context# (assoc context# :tx tx#)
               ~name-sym (assoc context# :memos (context-memos context#))]
           ~@body)))))

(defn create-dataset! [context {:keys [input]} _]
  (ensure-sysrev-dev
   context
   (with-tx-context [context context]
     (denamespace-keys
      (execute-one!
       context
       {:insert-into :dataset
        :values [input]
        :returning (-> context current-selection-names (conj :id) seq)})))))

(defn resolve-dataset [context {:keys [id]} _]
  (with-tx-context [context context]
    (when-not (public-dataset? context id)
      (ensure-sysrev-dev context))
    (let [ks (conj (current-selection-names context) :id)]
      (if (= ks #{:id :indices})
        {:id id}
        (denamespace-keys
         (execute-one!
          context
          {:select (seq (disj ks :indices))
           :from :dataset
           :where [:= :id id]}))))))

(defn internal-path-vec
  "Returns a path vector with the :* keyword replaced with the string
  \":datapub/*\" for use in postgres."
  [path-seq]
  (mapv #(if (= :* %) ":datapub/*" %) path-seq))

(defn external-path-vec
  "The reverse of internal-path-vec."
  [path-seq]
  (mapv #(if (= ":datapub/*" %) :* %) path-seq))

(defn resolve-Dataset-indices [context _ {:keys [id]}]
  (with-tx-context [context context]
    (when-not (public-dataset? context id)
      (ensure-sysrev-dev context))
    (->> (execute!
          context
          {:select [:path :type-name]
           :from :dataset-index-spec
           :join [:index-spec [:= :id :index-spec-id]]
           :where [:= :dataset-id id]})
         (map
          (fn [{:index-spec/keys [path type-name]}]
            {:path (->> path .getArray
                        internal-path-vec
                        pr-str)
             :type (keyword (str/upper-case type-name))})))))

(defn list-datasets [context {first* :first :keys [after]} _]
  (ensure-sysrev-dev
   context
   (let [cursor (if (empty? after)
                  0
                  (try (Long/parseLong after) (catch Exception _)))]
     (if (and after (not (and cursor (nat-int? cursor))))
       (resolve/resolve-as nil {:message "Invalid cursor."
                                :cursor after})
       (with-tx-context [context context]
         (let [ks (current-selection-names context)
               ct (when (:totalCount ks)
                    (:count
                     (execute-one! context {:select :%count.id :from :dataset})))
               limit (inc (min 100 (or first* 100)))
               [edges more]
               #__ (when (and (or (nil? first*) (pos? first*))
                              (or (:edges ks) (:pageInfo ks)))
                     (->> (execute!
                           context
                           {:select :id
                            :from :dataset
                            :limit limit
                            :where [:> :id cursor]})
                          (map (fn [{:dataset/keys [id]}]
                                 {:cursor (str id) :node {:id id}}))
                          (split-at (dec limit))))]
           {:edges edges
            :pageInfo
            {:endCursor (:cursor (last edges) "")
             :hasNextPage (boolean (seq more))
             ;; The spec allows hasPreviousPage to return true when unknown.
             :hasPreviousPage (not (or (zero? cursor) (= ct (count edges))))
             :startCursor (:cursor (first edges) "")}
            :totalCount ct}))))))

(defn resolve-ListDatasetsEdge-node [context _ {:keys [node]}]
  (resolve-dataset context node _))

(defn resolve-dataset-entity [context {:keys [id]} _]
  (let [ks (conj (current-selection-names context) :dataset-id :id)
        ks (if (:externalId ks)
             (-> ks (disj :externalId) (conj :external-id))
             ks)]
    (with-tx-context [context context]
      (->>
       (if (or (:content ks) (:mediaType ks))
         (or
          (as-> context $
           (execute-one!
            $
            {:select (->> (if (:content ks)
                            (conj (disj ks :content) :hash)
                            ks)
                          (remove #{:mediaType}))
             :from :entity
             :where [:= :id id]
             :join [:content-file [:= :content-file.content-id :entity.content-id]]})
           (some-> $
                   (assoc :content (when (:content ks)
                                     (-> (file/get-entity-content
                                          (get-in context [:pedestal :s3])
                                          (:content-file/hash $))
                                         :Body
                                         IOUtils/toByteArray
                                         (->> (.encodeToString (Base64/getEncoder)))))
                          :mediaType "application/pdf")))
          (some->
           (execute-one!
            context
            {:select (->> (if (:content ks)
                            (conj (disj ks :content) [[:raw "content::text"]])
                            ks)
                          (remove #{:mediaType}))
             :from :entity
             :where [:= :id id]
             :join [:content-json [:= :content-json.content-id :entity.content-id]]})
           (assoc :mediaType "application/json")))
         (if (= #{:id} ks)
           {:id id}
           (execute-one!
            context
            {:select (seq ks)
             :from :entity
             :where [:= :id id]})))
       denamespace-keys
       (me/map-keys #(or ({:external-id :externalId} %) %))
       ((fn [{:keys [dataset-id] :as entity}]
          (if (or (sysrev-dev? context)
                  (call-memo context :public-dataset? dataset-id))
            entity
            (resolve/resolve-as nil {:message "You are not authorized to access entities in that dataset."
                                     :datasetId dataset-id}))))))))

(defn create-entity-helper!
  "To be called by the create-dataset-entity! methods. Takes the context,
  args, and a map like
  {:content {:a 2}
   :content-hash (byte-array (hasch/edn-hash {:a 2}))
   :content-table :content-json}
  or
  {:data {:text \"...\"
   :content-hash (byte-array ...))
   :content-table :content-file}

  Handles checking for dataset existence, for existing content with the same
  hash, and existing entities in the dataset with the same externalId."
  [context
   {:keys [datasetId externalId mediaType]}
   {:keys [content content-hash content-table data]}]
  (ensure-sysrev-dev
   context
   (with-tx-context [context context]
     (if (empty? (execute-one! context {:select :id
                                        :from :dataset
                                        :where [:= :id datasetId]}))
       (resolve/resolve-as nil {:message "There is no dataset with that id."
                                :datasetId datasetId})
       (let [existing-content-id
             #__ ((keyword (name content-table) "content-id")
                  (execute-one!
                   context
                   {:select :content-id
                    :from content-table
                    :where [:= :hash content-hash]}))
             values [(-> {:dataset-id datasetId
                          :content-id (or existing-content-id
                                          {:select :id :from :content})}
                         ((if externalId
                            #(assoc % :external-id externalId)
                            identity)))]]
         (if existing-content-id
           (if-let [ety (and externalId
                             (execute-one!
                              context
                              {:select :id
                               :from :entity
                               :where [:and
                                       [:= :dataset-id datasetId]
                                       [:= :external-id externalId]
                                       [:= :content-id existing-content-id]
                                       [:in :created
                                        {:select :%max.created
                                         :from [[:entity :e]]
                                         :where [:and
                                                 [:= :e.dataset-id datasetId]
                                                 [:= :e.external-id externalId]]}]]}))]
             (resolve-dataset-entity context {:id (:entity/id ety)} nil)
             (-> context
                 (execute-one!
                  {:insert-into :entity
                   :returning :id
                   :values values})
                 :entity/id
                 (#(resolve-dataset-entity context {:id %} nil))))
           (-> context
               (execute-one!
                {:with
                 [[:content
                   {:insert-into :content
                    :values [{:created [:now]}]
                    :returning :id}]
                  [content-table
                   {:insert-into content-table
                    :values
                    [(if (= :content-json content-table)
                       {:content (jsonb-pgobject content)
                        :content-id {:select :id :from :content}
                        :hash content-hash}
                       {:content-id {:select :id :from :content}
                        :hash content-hash
                        :data (jsonb-pgobject data)
                        :media-type mediaType})]}]]
                 :insert-into :entity
                 :returning :id
                 :values values})
               :entity/id
               (#(resolve-dataset-entity context {:id %} nil)))))))))

(defmulti create-dataset-entity! (fn [_ {:keys [mediaType]} _]
                                   (when mediaType
                                     (str/lower-case mediaType))))

(defmethod create-dataset-entity! :default
  [_ {:keys [mediaType]} _]
  (resolve/resolve-as nil {:message "Invalid media type."
                           :mediaType mediaType}))

(defmethod create-dataset-entity! "application/json"
  [context {:as args :keys [content]} _]
  (ensure-sysrev-dev
   context
   (let [json (try
                (json/parse-string content)
                (catch Exception e))]
     (if (empty? json)
       (resolve/resolve-as nil {:message "Invalid content: Not valid JSON."
                                :content content})
       (with-tx-context [context context]
         (create-entity-helper!
          context args
          {:content json
           :content-hash (byte-array (hasch/edn-hash json))
           :content-table :content-json}))))))

(defn condense-whitespace [s]
  (as-> (str/split s #"\-\n") $
      (str/join "" $)
      (str/split $ #"\s+")
      (str/join " " $)))

(defmethod create-dataset-entity! "application/pdf"
  [context {:as args :keys [content]} _]
  (ensure-sysrev-dev
   context
   (let [pdf (try
                (.decode (Base64/getDecoder) content)
                (catch Exception e))]
     (if (empty? pdf)
       (resolve/resolve-as nil {:message "Invalid content: Not valid base64."
                                :content content})
       (let [hash (.digest (MessageDigest/getInstance "SHA3-256") pdf)]
         ;; We put the file before parsing to minimize memory usage,
         ;; since we can let go of the pdf byte array earlier.
         (file/put-entity-content! (get-in context [:pedestal :s3])
                                   {:content pdf :content-hash hash})
         (let [file-opts (into-array [StandardCopyOption/REPLACE_EXISTING])
               text (file/with-temp-file [path {:suffix ".pdf"}]
                      (Files/copy (io/input-stream pdf) path file-opts)
                      (try
                        (pdf-read/with-PDDocument [doc (.toFile path)]
                          (pdf-read/get-text doc {:sort-by-position true}))
                        (catch IOException e
                          (if (= "Error: Header doesn't contain versioninfo"
                                 (.getMessage e))
                            :invalid-pdf
                            (throw e)))))]
           (if (= :invalid-pdf text)
             (resolve/resolve-as nil {:message "Invalid content: Not a valid PDF file."
                                      :content content})
             (with-tx-context [context context]
               (create-entity-helper!
                context args
                {:content-hash hash
                 :content-table :content-file
                 :data {:text (condense-whitespace text)}})))))))))

(defn dataset-entities-subscription [context {:keys [datasetId uniqueExternalIds]} source-stream]
  (ensure-sysrev-dev
   context
   (let [q {:select :id
            :from :entity
            :where [:and
                    [:= :dataset-id datasetId]
                    (when uniqueExternalIds
                      [:in :created
                       {:select :%max.created
                        :from [[:entity :e]]
                        :where [:and
                                [:= :e.dataset-id :entity.dataset-id]
                                [:= :e.external-id :entity.external-id]]}])]}
         fut (future
               (try
                 (with-tx-context [context context]
                   (reduce
                    (fn [_ row]
                      (source-stream
                       (resolve-dataset-entity context {:id (:id row)} nil)))
                    nil
                    (plan context q))
                   (source-stream nil))
                 (catch Exception e
                   (prn e))))]
     (fn [] (future-cancel fut)))))

(defn valid-index-path? [path-vec]
  (and (vector? path-vec)
       (seq path-vec)
       (every? #(or (string? %)
                    (nat-int? %)
                    (= :* %))
               path-vec)))

(defn get-index-spec [context path-vec type]
  (let [path-vec (internal-path-vec path-vec)]
    (with-tx-context [context context]
      (execute-one!
       context
       {:select :*
        :from :index-spec
        :where
        [:and [:= :path [:array path-vec]] [:= :type-name type]]}))))

(defn get-or-create-index-spec! [context path-vec type]
  (or (get-index-spec context path-vec type)
      (let [path-vec (internal-path-vec path-vec)]
        (with-tx-context [context context]
          (execute-one!
           context
           {:insert-into :index-spec
            :values [{:path [:array path-vec] :type-name type}]
            :on-conflict []
            :do-nothing []})
          (get-index-spec context path-vec type)))))

(defn resolve-dataset-index [context {:keys [datasetId path type]} _]
  (let [path-vec (try (edn/read-string path) (catch Exception _))]
    (when (valid-index-path? path-vec)
      (with-tx-context [context context]
        (when-not (public-dataset? context datasetId)
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
                       [:= :dataset-id datasetId]
                       [:= :index-spec-id $]]})
           (when $
             {:path (pr-str path-vec)
              :type type})))))))

(defn create-dataset-index! [context {:keys [datasetId path type]} _]
  (ensure-sysrev-dev
   context
   (let [path-vec (try (edn/read-string path) (catch Exception _))]
     (if-not (valid-index-path? path-vec)
       (resolve/resolve-as nil {:message "Invalid index path."
                                :path path}))
     (with-tx-context [context context]
       (if (empty? (execute-one! context {:select :id
                                          :from :dataset
                                          :where [:= :id datasetId]}))
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
             [{:dataset-id datasetId
               :index-spec-id index-spec-id}]})
           (resolve-dataset-index context {:datasetId datasetId :path path :type type} nil)))))))

(defn string-query->sqlmap [context {:keys [eq ignoreCase path]}]
  (let [path-vec (-> (try (edn/read-string path)
                          (catch Exception _
                            (throw (ex-info "Invalid path." {:path path}))))
                     internal-path-vec)
        v [(keyword "#>>") :indexed-data [:array path-vec]]
        maybe-lower #(if ignoreCase [:lower %] %)]
    [:in
     (maybe-lower (pr-str eq))
     {:select
      [[(maybe-lower
         [:unnest [:cast
                   [:get_in_jsonb :indexed-data [:array path-vec]]
                   [:raw "text[]"]]])]]}]))

(defn text-query->sqlmap [context {:keys [paths search useEveryIndex]}]
  (when (if useEveryIndex paths (not paths))
    (throw (RuntimeException. "Either paths or useEveryIndex must be specified, but not both.")))
  (let [dataset-id (::dataset-id context)
        index-spec-ids
        #__ (if useEveryIndex
              (->> {:select :index-spec-id
                     :from :dataset-index-spec
                     :join [:index-spec [:= :id :index-spec-id]]
                     :where [:and
                             [:= :dataset-id dataset-id]
                             [:= "text" :index-spec.type-name]]}
                   (execute! context)
                   (map :dataset-index-spec/index-spec-id))
              (->> paths
                   (map
                    (fn [path]
                      (let [path-vec (try (edn/read-string path)
                                          (catch Exception _))]
                        (or
                         (when (valid-index-path? path-vec)
                           (:index-spec/id
                            (get-index-spec context path-vec "text")))
                         (throw (ex-info "Index does not exist."
                                         {:path path}))))))))]
    (some->> index-spec-ids
             (map #(vector (keyword "@@")
                           (keyword (str "index-" %))
                           [:websearch_to_tsquery
                            [:raw "'english'::regconfig"]
                            search]))
             seq
             (apply vector :or))))

(defn search-dataset-query->sqlmap [context {:keys [query string text type]}]
  (apply
   vector
   (case type
     :AND :and
     :OR :or)
   (concat
    (map (partial search-dataset-query->sqlmap context) query)
    (map (partial string-query->sqlmap context) string)
    (map (partial text-query->sqlmap context) text))))

(defn search-dataset-subscription
  [context
   {{:keys [datasetId query uniqueExternalIds] :as args} :input}
   source-stream]
  (if-not (or (sysrev-dev? context)
              (with-tx-context [context context]
                (public-dataset? context datasetId)))
    (do
      (source-stream
       (resolve/resolve-as nil {:datasetId datasetId
                                :message "You are not authorized to access entities in that dataset."}))
      (constantly nil))
    (let [fut (future
                (try
                  (with-tx-context [context (assoc context ::dataset-id 1)]
                    (let [indexed-entity-table (keyword (str "indexed-entity-" datasetId))
                          q (search-dataset-query->sqlmap context query)]
                      (when (some identity (rest q))
                        (reduce
                         (fn [_ row]
                           (source-stream
                            (resolve-dataset-entity context {:id (:entity_id row)} nil)))
                         nil
                         (plan
                          context
                          {:select :entity-id
                           :from indexed-entity-table
                           :where q}))))
                    (source-stream nil))
                  (catch Exception e
                    (prn e))))]
      (fn [] (future-cancel fut)))))
