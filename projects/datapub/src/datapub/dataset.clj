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
            [sysrev.file-util.interface :as file-util]
            [sysrev.pdf-read.interface :as pdf-read]
            [sysrev.tesseract.interface :as tesseract])
  (:import java.awt.image.BufferedImage
           [java.io InputStream IOException]
           java.nio.file.Path
           java.sql.Timestamp
           [java.time Instant ZoneId]
           java.time.format.DateTimeFormatter
           java.util.Base64
           org.apache.commons.io.IOUtils
           org.postgresql.jdbc.PgArray
           org.postgresql.util.PGobject))

(set! *warn-on-reflection* true)

(defn jsonb-pgobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string x))))

(defn sysrev-dev? [context]
  (let [auth (-> context :request :headers (get "authorization")
                 (or (-> context
                         :com.walmartlabs.lacinia/connection-params
                         :authorization)))
        sysrev-dev-key (-> context :pedestal :opts :sysrev-dev-key)]
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
      (->> (map (comp selection/field-name selection/field)))
      set))

(defn denamespace-keys [map-or-seq]
  (cond (map? map-or-seq) (me/map-keys (comp keyword name) map-or-seq)
        (sequential? map-or-seq) (map denamespace-keys map-or-seq)))

(defn remap-keys
  "Removes namespaces from keys and remaps the keys by key-f."
  [key-f map-or-seq]
  (cond (map? map-or-seq) (->> map-or-seq
                               denamespace-keys
                               (me/map-keys key-f))
        (sequential? map-or-seq) (map (partial remap-keys key-f) map-or-seq)))

(defn public-dataset? [context id]
  (-> context
      (execute-one! {:select :public :from :dataset :where [:= :id id]})
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

(let [cols {:created :created
            :description :description
            :name :name
            :public :public}
      inv-cols (me/map-kv (fn [k v] [v k]) cols)]

  (defn resolve-dataset [context {:keys [id]} _]
    (with-tx-context [context context]
      (when-not (public-dataset? context id)
        (ensure-sysrev-dev context))
      (let [ks (conj (current-selection-names context) :id)
            select (keep cols ks)]
        (-> (when (seq select)
              (remap-keys
               inv-cols
               (execute-one!
                context
                {:select select
                 :from :dataset
                 :where [:= :id id]})))
            (assoc :id id)))))

  (defn create-dataset! [context {:keys [input]} _]
    (ensure-sysrev-dev
     context
     (with-tx-context [context context]
       (let [{:dataset/keys [id]}
             #__ (execute-one!
                  context
                  {:insert-into :dataset
                   :values [(me/map-keys cols input)]
                   :returning :id})]
         (when id
           (resolve-dataset context {:id id} nil))))))

  (defn update-dataset! [context {{:keys [id] :as input} :input} _]
    (ensure-sysrev-dev
     context
     (with-tx-context [context context]
       (let [set (me/map-keys cols (dissoc input :id))
             {:dataset/keys [id]}
             #__ (if (empty? set)
                   {:dataset/id id}
                   (execute-one!
                    context
                    {:update :dataset
                     :set set
                     :where [:= :id id]
                     :returning :id}))]
         (when id
           (resolve-dataset context {:id id} nil)))))))

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
            {:path (->> ^PgArray path
                        .getArray
                        internal-path-vec
                        pr-str)
             :type (keyword (str/upper-case type-name))})))))

(defn connection-helper
  "Helper to resolve GraphQL Cursor Connections as specified by
  https://relay.dev/graphql/connections.htm

  Look at list-datasets for an example implementation."
  [context {first* :first :keys [after]} {:keys [count-f edges-f]}]
  (with-tx-context [context context]
   (let [cursor (if (empty? after)
                  0
                  (try (Long/parseLong after) (catch Exception _)))]
     (if (and after (not (and cursor (nat-int? cursor))))
       (resolve/resolve-as nil {:message "Invalid cursor."
                                :cursor after})
       (with-tx-context [context context]
         (let [ks (current-selection-names context)
               ct (when (:totalCount ks)
                    (count-f {:context context}))
               limit (inc (min 100 (or first* 100)))
               [edges more] (when (and (or (nil? first*) (pos? first*))
                                       (or (:edges ks) (:pageInfo ks)))
                              (edges-f {:context context
                                        :cursor cursor
                                        :limit limit}))]
           {:edges edges
            :pageInfo
            {:endCursor (:cursor (last edges) "")
             :hasNextPage (boolean (seq more))
             ;; The spec allows hasPreviousPage to return true when unknown.
             :hasPreviousPage (not (or (zero? cursor) (= ct (count edges))))
             :startCursor (:cursor (first edges) "")}
            :totalCount ct}))))))

(defn list-datasets [context args _]
  (connection-helper
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
                  {:cursor (str id) :node {:id id}}))
           (split-at (dec limit))))}))

(defn resolve-ListDatasetsEdge-node [context _ {:keys [node]}]
  (resolve-dataset context node _))

(defn server-url [{:keys [scheme server-name server-port]}]
  (str (name scheme) "://" server-name
       (when-not (or (and (= 80 server-port (= :http scheme)))
                     (and (= 443 server-port (= :https scheme))))
         (str ":" server-port))))

(defn base64url-encode [^bytes bytes]
  (String. (.encode (Base64/getUrlEncoder) bytes)))

(defn content-url-path
  "Returns the path for an entity's content.

  Part of the hash is included as a cache-buster for dev
  and staging servers."
  [entity-id ^bytes hash]
  (str "/download/DatasetEntity/content/" entity-id
       "/" (-> hash base64url-encode (subs 0 21))))

(let [cols-all {:created :created
                :dataset-id :dataset-id
                :externalCreated :external-created
                :externalId :external-id
                :groupingId :grouping-id}
      inv-cols (into {} (map (fn [[k v]] [v k]) cols-all))
      cols-file (assoc cols-all
                       :content :file-hash
                       :content-hash :content-hash
                       :metadata [[:raw "data->>'metadata' as metadata"]])
      cols-json (assoc cols-all
                       :content [[:raw "content::text"]]
                       :content-hash :hash
                       :externalId :external-id)]

  (defn get-entity-content [context id ks]
    (or
     (as-> context $
       (execute-one!
        $
        {:select (keep cols-file ks)
         :from :entity
         :where [:= :id id]
         :join [:content-file [:= :content-file.content-id :entity.content-id]]})
       (some->
        $
        (assoc :content (when (:content ks)
                          (-> (file/get-entity-content
                               (get-in context [:pedestal :s3])
                               (:content-file/file-hash $))
                              :Body))
               :mediaType "application/pdf")))
     (some->
      (execute-one!
       context
       {:select (keep cols-json ks)
        :from :entity
        :where [:= :id id]
        :join [:content-json [:= :content-json.content-id :entity.content-id]]})
      (assoc :mediaType "application/json"))))

  (defn resolve-dataset-entity [context {:keys [id]} _]
    (let [ks (conj (current-selection-names context) :dataset-id)]
      (with-tx-context [context context]
        (as->
            (if (some #{:content :contentUrl :mediaType :metadata} ks)
              (-> (get-entity-content
                   context id
                   (conj ks (when (:contentUrl ks) :content-hash)))
                  (update :content
                          #(if (instance? InputStream %)
                             (.encodeToString (Base64/getEncoder)
                                              (IOUtils/toByteArray ^InputStream %))
                             %)))
              (execute-one!
               context
               {:select (keep cols-all ks)
                :from :entity
                :where [:= :id id]}))
            $
          (remap-keys #(inv-cols % %) $)
          (assoc $
                 :id id
                 :contentUrl
                 (when (:contentUrl ks)
                   (str (server-url (:request context))
                        (content-url-path id (or (:content-hash $) (:hash $))))))
          (if (or (sysrev-dev? context)
                  (call-memo context :public-dataset? (:dataset-id $)))
            $
            (resolve/resolve-as nil {:message "You are not authorized to access entities in that dataset."
                                     :datasetId (:dataset-id $)})))))))

(defn resolve-Dataset-entities
  [context {:keys [externalId groupingId :as args]} {:keys [id]}]
  (let [where [:and
               [:= :dataset-id id]
               (when externalId
                 [:= :external-id externalId])
               (when groupingId
                 [:= :grouping-id groupingId])]]
    (connection-helper
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
                    {:cursor (str id) :node {:id id}}))
             (split-at (dec limit))))})))

(defn resolve-DatasetEntitiesEdge-node [context _ {:keys [node]}]
  (resolve-dataset-entity context node _))

(def ^DateTimeFormatter http-datetime-formatter
  (.withZone DateTimeFormatter/RFC_1123_DATE_TIME
             (ZoneId/systemDefault)))

(defn download-DatasetEntity-content [context allowed-origins]
  (let [request (:request context)
        entity-id (try
                    (some-> request :path-params :entity-id
                            Long/parseLong)
                    (catch Exception _))]
    (when entity-id
      (with-tx-context [context context]
        (let [entity (get-entity-content
                      context entity-id
                      #{:content :content-hash :created :dataset-id})]
          (when (:content entity)
            (let [hash (or (:content-json/hash entity) (:content-file/content-hash entity))
                  etag (base64url-encode hash)
                  origin (some-> (get-in request [:headers "origin"]) str/lower-case)
                  public? (call-memo context :public-dataset? (:entity/dataset-id entity))]
              (cond
                (not (or (sysrev-dev? context) public?))
                {:status 403
                 :body "Forbidden"}

                (not= (:content-hash (:path-params request)) (subs etag 0 21))
                {:status 301
                 :headers (cond-> {"Location" (content-url-path entity-id hash)}
                            (and origin (allowed-origins origin))
                            #__ (assoc "Access-Control-Allow-Origin" origin))}

                :else
                {:status 200
                 :headers (cond-> {"Cache-Control" (if public?
                                                     "public, max-age=315360000, immutable"
                                                     "no-cache")
                                   "Content-Type" (:mediaType entity)
                                   "ETag" etag
                                   "Last-Modified" (.format
                                                    http-datetime-formatter
                                                    (.toInstant ^Timestamp (:entity/created entity)))}
                            (and origin (allowed-origins origin))
                            #__ (assoc "Access-Control-Allow-Origin" origin))
                 :body (when (not= :head (:request-method request))
                         (:content entity))}))))))))

(defn get-existing-content-id [context {:keys [content-hash content-table]}]
  ((keyword (name content-table) "content-id")
   (execute-one!
    context
    {:select :content-id
     :from content-table
     :where [:= content-hash
             (if (= :content-json content-table) :hash :content-hash)]})))

(defn get-existing-entity
  [context {:keys [content-id dataset-id external-created external-id grouping-id]}]
  (execute-one!
   context
   {:select :id
    :from :entity
    :where [:and
            [:= :content-id content-id]
            [:= :dataset-id dataset-id]
            [:= :external-created external-created]
            [:= :external-id external-id]
            [:= :grouping-id grouping-id]]}))

(defn create-entity-helper!
  "To be called by the create-dataset-entity! methods. Takes the context,
  args, and a map like
  {:content {:a 2}
   :content-hash (byte-array (hasch/edn-hash {:a 2}))
   :content-table :content-json}
  or
  {:data {:metadata {...} :text \"...\"}
   :content-hash (byte-array ...))
   :content-table :content-file
   :file-hash (byte-array ...)}

  Handles checking for dataset existence, for existing content with the same
  hash, and existing entities in the dataset with the same externalId."
  [context
   {:keys [datasetId ^Instant externalCreated externalId groupingId mediaType]}
   {:keys [content content-hash content-table data file-hash]}]
  (ensure-sysrev-dev
   context
   (with-tx-context [context context]
     (if (empty? (execute-one! context {:select :id
                                        :from :dataset
                                        :where [:= :id datasetId]}))
       (resolve/resolve-as nil {:message "There is no dataset with that id."
                                :datasetId datasetId})
       (let [existing-content-id
             #__ (get-existing-content-id
                  context
                  {:content-hash content-hash :content-table content-table})
             external-created (some-> externalCreated .toEpochMilli Timestamp.)
             identifiers {:content-id existing-content-id
                          :dataset-id datasetId
                          :external-created external-created
                          :external-id externalId
                          :grouping-id groupingId}]
         (if existing-content-id
           (if-let [existing-entity (get-existing-entity context identifiers)]
             (resolve-dataset-entity context {:id (:entity/id existing-entity)} nil)
             ;; Insert entity referencing existing content
             (-> context
                 (execute-one!
                  {:insert-into :entity
                   :returning :id
                   :values [identifiers]})
                 :entity/id
                 (#(resolve-dataset-entity context {:id %} nil))))
           ;; Create new content and insert entity referencing it
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
                       {:content-hash content-hash
                        :content-id {:select :id :from :content}
                        :data (jsonb-pgobject data)
                        :file-hash file-hash
                        :media-type mediaType})]}]]
                 :insert-into :entity
                 :returning :id
                 :values [(-> {:dataset-id datasetId
                               :content-id (or existing-content-id
                                               {:select :id :from :content})
                               :external-created external-created}
                              ((if externalId
                                 #(assoc % :external-id externalId)
                                 identity))
                              ((if groupingId
                                 #(assoc % :grouping-id groupingId)
                                 identity)))]})
               :entity/id
               (#(resolve-dataset-entity context {:id %} nil)))))))))

(defmulti create-dataset-entity! (fn [_ {{:keys [mediaType]} :input} _]
                                   (when mediaType
                                     (str/lower-case mediaType))))

(defmethod create-dataset-entity! :default
  [_ {{:keys [mediaType]} :input} _]
  (resolve/resolve-as nil {:message "Invalid media type."
                           :mediaType mediaType}))

(defmethod create-dataset-entity! "application/json"
  [context {{:keys [content contentUpload metadata] :as input} :input} _]
  (ensure-sysrev-dev
   context
   (let [json (try
                (json/parse-string
                 (or content
                     (when (string? contentUpload) contentUpload)
                     (-> contentUpload :tempfile io/file slurp)))
                (catch Exception e))]
     (if (empty? json)
       (resolve/resolve-as nil {:message "Invalid content: Not valid JSON."})
       (if (seq metadata)
         (resolve/resolve-as nil {:message "JSON entities cannot have metadata."})
         (with-tx-context [context context]
           (create-entity-helper!
            context input
            {:content json
             :content-hash (byte-array (hasch/edn-hash json))
             :content-table :content-json})))))))

(defn condense-whitespace [s]
  (as-> (str/split s #"\-\n") $
      (str/join "" $)
      (str/split $ #"\s+")
      (str/join " " $)))

(defn pdf-text [^Path path tesseract]
  (try
    (pdf-read/with-PDDocument [doc (.toFile path)]
      (let [text (condense-whitespace
                  (pdf-read/get-text doc {:sort-by-position true}))]
        (if-not (str/blank? text)
          {:text text}
          (when (:enabled? tesseract)
            {:ocr-text
             (let [tess (tesseract/tesseract
                         {:data-path (System/getenv "TESSDATA_PREFIX")})]
               (->> doc pdf-read/->image-seq
                    (map
                     (fn [^BufferedImage image]
                       (.doOCR tess image)))
                    (apply str)
                    condense-whitespace))}))))
    (catch IOException e
      (if (= "Error: Header doesn't contain versioninfo"
             (.getMessage e))
        :invalid-pdf
        (throw e)))))

(defmethod create-dataset-entity! "application/pdf"
  [context {{:keys [content contentUpload metadata] :as input} :input :as args} value]
  (ensure-sysrev-dev
   context
   (let [json (try
                (when metadata (json/parse-string metadata))
                (catch Exception _))
         {:keys [path]} contentUpload]
     (cond
       (and content contentUpload)
       (resolve/resolve-as nil {:message "Either content or contentUpload must be specified, but not both."})

       (or content (string? contentUpload))
       (let [pdf (try
                   (.decode (Base64/getDecoder) (or ^String content ^String contentUpload))
                   (catch Exception _))]
         (if pdf
           (file-util/with-temp-file [path {:prefix "datapub-"
                                            :suffix ".pdf"}]
             (file-util/copy! (io/input-stream pdf) path
                              #{:replace-existing})
             (create-dataset-entity!
              context
              (-> (update args :input dissoc :content)
                  (assoc-in [:input :contentUpload] {:path path}))
              value))
           (resolve/resolve-as nil {:message "Invalid content: Not valid base64."})))

       (and metadata (empty? json))
       (resolve/resolve-as nil {:message "Invalid metadata: Not valid JSON."
                                :metadata metadata})

       :else
       (let [text (pdf-text path (get-in context [:pedestal :config :tesseract]))]
         (if (= :invalid-pdf text)
           (resolve/resolve-as nil {:message (str "Invalid content or contentUpload: Not a valid PDF file.")})
           (let [file-hash (file/file-sha3-256 path)
                 data (->> (assoc text :metadata json)
                           (me/remove-vals nil?))
                 content-hash (-> {:data data
                                   :file-hash
                                   (.encode (Base64/getEncoder) file-hash)}
                                  hasch/edn-hash
                                  byte-array)]
             (file/put-entity-content! (get-in context [:pedestal :s3])
                                       {:content (-> ^Path path .toUri .toURL .openStream)
                                        :file-hash file-hash})
             (with-tx-context [context context]
               (create-entity-helper!
                context input
                {:content-hash content-hash
                 :content-table :content-file
                 :data data
                 :file-hash file-hash})))))))))

(defn dataset-entities-subscription [context {{:keys [datasetId uniqueExternalIds uniqueGroupingIds]} :input} source-stream]
  (ensure-sysrev-dev
   context
   (let [q {:select :id
            :from :entity
            :where [:and
                    [:= :dataset-id datasetId]
                    (when (or uniqueExternalIds uniqueGroupingIds)
                      [:in [:concat [:coalesce :external-created :created] :created]
                       {:select [[[:max [:concat [:coalesce :external-created :created] :created]]]]
                        :from [[:entity :e]]
                        :where [:and
                                [:= :e.dataset-id :entity.dataset-id]
                                (if uniqueExternalIds
                                  [:= :e.external-id :entity.external-id]
                                  [:= :e.grouping-id :entity.grouping-id])]}])]}
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

(defn create-dataset-index! [context {{:keys [datasetId path type]} :input} _]
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
           (resolve-dataset-index
            context
            {:datasetId datasetId
             :path (pr-str (-> path-vec internal-path-vec external-path-vec))
             :type type}
            nil)))))))

(defn string-query->sqlmap [_ {:keys [eq ignoreCase path]}]
  (let [path-vec (internal-path-vec (edn/read-string path))
        v [(keyword "#>>") :indexed-data [:array path-vec]]
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
  (let [index-spec-ids (reduce
                        #(assoc % %2 (get-index-spec-ids-for-path context datasetId %2))
                        {}
                        (get-search-query-paths query))
        where (search-dataset-query->sqlmap index-spec-ids query)
        q {:select :entity-id
           :from (keyword (str "indexed-entity-" datasetId))
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

    (not (or (sysrev-dev? context)
             (with-tx-context [context context]
               (public-dataset? context datasetId))))
    (fail-subscription
     source-stream
     {:datasetId datasetId
      :message "You are not authorized to access entities in that dataset."})

    :else
    (let [fut (future
                (try
                  (with-tx-context [context context]
                    (let [q (search-dataset-query->select context input)]
                      (when (some identity (rest q))
                        (reduce
                         (fn [_ row]
                           (source-stream
                            (resolve-dataset-entity context {:id (:entity_id row)} nil)))
                         nil
                         (plan context q))))
                    (source-stream nil))
                  (catch Exception e
                    (prn e))))]
      (fn [] (future-cancel fut)))))
