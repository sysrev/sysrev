(ns datapub.dataset.entity
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datapub.auth :as auth]
            [datapub.file :as file]
            [datapub.util :as util :refer [ensure-sysrev-dev execute! execute-one! plan with-tx-context]]
            [hasch.core :as hasch]
            [medley.core :as medley]
            [sysrev.file-util.interface :as file-util]
            [sysrev.lacinia.interface :as sl]
            [sysrev.pdf-read.interface :as pdf-read]
            [sysrev.postgres.interface :as pg]
            [sysrev.ris.interface :as ris]
            [sysrev.tesseract.interface :as tesseract])
  (:import (java.io InputStream)
           (java.nio.file Path)
           (java.sql Timestamp)
           (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Base64)
           (org.apache.commons.io IOUtils)))

(def
  ^{:doc "Media types that can be returned as a content property in plain text"}
  ^:const text-media-types
  #{"applicaton/json"
    "application/x-research-info-systems"})

(defn call-memo [context kw & args]
  (apply (get-in context [:memos kw]) args))

(defn server-url [{:keys [headers scheme server-name server-port]}]
  (str (or (some-> headers (get "x-forwarded-proto") str/lower-case #{"http" "https"})
           (name scheme))
       "://" server-name
       (when-not (#{80 443} server-port)
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
      cols-inv (into {} (map (fn [[k v]] [v k]) cols-all))
      cols-file (assoc cols-all
                       :content :file-hash
                       :content-hash :content-hash
                       :file-hash :file-hash
                       :metadata [[:raw "data->>'metadata' as metadata"]])
      cols-json (assoc cols-all
                       :content [[:raw "content::text"]]
                       :content-hash :hash
                       :externalId :external-id)]

  (defn get-entity-content [context ^Long int-id ks]
    (or
     (as-> context $
       (execute-one!
        $
        {:select (-> (keep cols-file ks) set (conj :media-type) vec)
         :from :entity
         :where [:= :id int-id]
         :join [:content-file [:= :content-file.content-id :entity.content-id]]})
       (some->
        $
        (assoc :content (when (:content ks)
                          (-> (file/get-entity-content
                               (get-in context [:pedestal :s3])
                               (:content-file/file-hash $))
                              :Body))
               :mediaType (:content-file/media-type $))))
     (some->
      (execute-one!
       context
       {:select (keep cols-json ks)
        :from :entity
        :where [:= :id int-id]
        :join [:content-json [:= :content-json.content-id :entity.content-id]]})
      (assoc :mediaType "application/json"))))

  (defn resolve-dataset-entity [context {:keys [id]} _]
    (when-let [int-id (sl/parse-int-id id)]
      (let [ks (conj (sl/current-selection-names context) :dataset-id)]
        (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
          (some->
           (if (some #{:content :contentUrl :mediaType :metadata} ks)
             (let [{:as m :keys [content mediaType]}
                   (get-entity-content
                    context int-id
                    (apply conj (conj ks :mediaType) (when (:contentUrl ks) [:content-hash :file-hash])))]
               (if (instance? InputStream content)
                 (assoc m :content
                        (if (text-media-types mediaType)
                          (slurp ^InputStream content)
                          (.encodeToString (Base64/getEncoder)
                                           (IOUtils/toByteArray ^InputStream content))))
                 m))
             (execute-one!
              context
              {:select (keep cols-all ks)
               :from :entity
               :where [:= :id int-id]}))
           (as-> $
                 (sl/remap-keys #(cols-inv % %) $)
             (assoc $
                    :id id
                    :contentUrl
                    (when (:contentUrl ks)
                      (let [domain (get-in context [:pedestal :config :files-domain-name])
                            {:keys [file-hash]} $]
                        (if (and domain file-hash)
                          (str "https://" domain "/" (file/content-key file-hash))
                          (str (server-url (:request context))
                               (content-url-path id (or (:content-hash $) (:hash $))))))))
             (if (or (util/sysrev-dev? context)
                     (call-memo context :public-dataset? (:dataset-id $))
                     (auth/can-read-dataset? context (:dataset-id $)))
               $
               (resolve/resolve-as nil {:message "You are not authorized to access entities in that dataset."
                                        :datasetId (:dataset-id $)})))))))))

(defn resolve-datasetEntitiesById
  [context {:keys [ids] :as args} _]
  (let [int-ids (keep sl/parse-int-id ids)]
    (when (seq int-ids)
      (util/connection-helper
       context args
       {:count-f
        (fn [{:keys [context]}]
          (->> {:select :%count.id
                :from :entity
                :where [:in :id int-ids]}
               (execute-one! context)
               :count))
        :edges-f
        (fn [{:keys [context cursor limit]}]
          (->> {:select :id
                :from :entity
                :limit limit
                :where [:and [:> :id cursor] [:in :id int-ids]]
                :order-by [:id]}
               (execute! context)
               (map (fn [{:entity/keys [id]}]
                      {:cursor (str id) :node {:id (str id)}}))
               (split-at (dec limit))))}))))

(def ^DateTimeFormatter http-datetime-formatter
  (.withZone DateTimeFormatter/RFC_1123_DATE_TIME
             (ZoneId/systemDefault)))

(defn download-DatasetEntity-content [context allowed-origins]
  (let [request (:request context)
        entity-id (some-> request :path-params :entity-id parse-long)]
    (when entity-id
      (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
        (let [{:keys [content] :as entity}
              #__ (get-entity-content
                   context entity-id
                   #{:content :content-hash :created :dataset-id})]
          (when content
            (let [hash (or (:content-json/hash entity) (:content-file/content-hash entity))
                  etag (base64url-encode hash)
                  origin (some-> (get-in request [:headers "origin"]) str/lower-case)
                  {:entity/keys [dataset-id]} entity
                  public? (call-memo context :public-dataset? dataset-id)
                  response
                  #__ (cond
                        (not (or (util/sysrev-dev? context) public?
                                 (auth/can-read-dataset? context dataset-id)))
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
                                                            (.toInstant ^Timestamp (:entity/created entity)))
                                           "Vary" "Origin"}
                                    (and origin (allowed-origins origin))
                                    #__ (assoc "Access-Control-Allow-Origin" origin))
                         :body (when (not= :head (:request-method request))
                                 (if (instance? java.io.InputStream content)
                                   (java.nio.channels.Channels/newChannel ^java.io.InputStream content)
                                   content))})]
              (when (and (instance? java.io.Closeable content)
                         (not (instance? java.io.Closeable (:body response))))
                (.close content))
              response)))))))

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
   (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
     (if (empty? (execute-one! context {:select :id
                                        :from :dataset
                                        :where [:= :id (sl/parse-int-id datasetId)]}))
       (resolve/resolve-as nil {:message "There is no dataset with that id."
                                :datasetId datasetId})
       (let [dataset-int-id (sl/parse-int-id datasetId)
             existing-content-id
             #__ (get-existing-content-id
                  context
                  {:content-hash content-hash :content-table content-table})
             external-created (some-> externalCreated .toEpochMilli Timestamp.)
             identifiers {:content-id existing-content-id
                          :dataset-id dataset-int-id
                          :external-created external-created
                          :external-id externalId
                          :grouping-id groupingId}]
         (if existing-content-id
           (if-let [existing-entity (get-existing-entity context identifiers)]
             (resolve-dataset-entity context {:id (str (:entity/id existing-entity))} nil)
             ;; Insert entity referencing existing content
             (-> context
                 (execute-one!
                  {:insert-into :entity
                   :returning :id
                   :values [identifiers]})
                 :entity/id str
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
                       {:content (pg/jsonb-pgobject content)
                        :content-id {:select :id :from :content}
                        :hash content-hash}
                       {:content-hash content-hash
                        :content-id {:select :id :from :content}
                        :data (pg/jsonb-pgobject data)
                        :file-hash file-hash
                        :media-type mediaType})]}]]
                 :insert-into :entity
                 :returning :id
                 :values [(-> {:dataset-id dataset-int-id
                               :content-id (or existing-content-id
                                               {:select :id :from :content})
                               :external-created external-created}
                              ((if externalId
                                 #(assoc % :external-id externalId)
                                 identity))
                              ((if groupingId
                                 #(assoc % :grouping-id groupingId)
                                 identity)))]})
               :entity/id str
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
                (catch Exception _))]
     (if (empty? json)
       (resolve/resolve-as nil {:message "Invalid content: Not valid JSON."})
       (if (seq metadata)
         (resolve/resolve-as nil {:message "JSON entities cannot have metadata."})
         (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
           (create-entity-helper!
            context input
            {:content json
             :content-hash (byte-array (hasch/edn-hash json))
             :content-table :content-json})))))))

(defn create-entity-pdf! [context input path metadata]
  (let [text (if (get-in context [:pedestal :config :tesseract :enabled?])
               (tesseract/read-text path :invalid-pdf)
               (as-> (pdf-read/read-text path :invalid-pdf) $
                 (if (= :invalid-pdf $) $ {:text $})))]
    (if (= :invalid-pdf text)
      (resolve/resolve-as nil {:message (str "Invalid content or contentUpload: Not a valid PDF file.")})
      (let [file-hash (file/file-sha3-256 path)
            data (->> (assoc text :metadata metadata)
                      (medley/remove-vals nil?))
            content-hash (-> {:data data
                              :file-hash
                              (.encode (Base64/getEncoder) file-hash)}
                             hasch/edn-hash
                             byte-array)]
        (file/put-entity-content! (get-in context [:pedestal :s3])
                                  {:content (-> ^Path path .toUri .toURL .openStream)
                                   :file-hash file-hash})
        (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
          (create-entity-helper!
           context input
           {:content-hash content-hash
            :content-table :content-file
            :data data
            :file-hash file-hash}))))))

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
       (create-entity-pdf! context input path json)))))

(defn create-entity-xml! [context input path metadata]
  (if-not (file/valid-xml? path)
    (resolve/resolve-as nil {:message (str "Invalid content or contentUpload: Not a valid XML file.")})
    (let [file-hash (file/file-sha3-256 path)
          data {:metadata metadata}
          content-hash (-> {:data data
                            :file-hash
                            (.encode (Base64/getEncoder) file-hash)}
                           hasch/edn-hash
                           byte-array)]
      (file/put-entity-content! (get-in context [:pedestal :s3])
                                {:content (-> ^Path path .toUri .toURL .openStream)
                                 :file-hash file-hash})
      (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
        (create-entity-helper!
         context input
         {:content-hash content-hash
          :content-table :content-file
          :data data
          :file-hash file-hash})))))

(defmethod create-dataset-entity! "application/xml"
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
       (let [xml (try
                   (.decode (Base64/getDecoder) (or ^String content ^String contentUpload))
                   (catch Exception _))]
         (if xml
           (file-util/with-temp-file [path {:prefix "datapub-"
                                            :suffix ".xml"}]
             (file-util/copy! (io/input-stream xml) path
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
       (create-entity-xml! context input path json)))))

(defmethod create-dataset-entity! "application/x-research-info-systems"
  [context {{:keys [content contentUpload metadata] :as input} :input} _]
  (ensure-sysrev-dev
   context
   (let [content (or content
                     (when (string? contentUpload) contentUpload)
                     (some-> contentUpload :path io/file slurp)
                     (some-> contentUpload :tempfile io/file slurp))
         json (try
                (when metadata (json/parse-string metadata))
                (catch Exception _))
         ;; Normalize the content
         ris (try
               (vec (ris/str->ris-maps content))
               (catch Exception _))]
     (cond
       (empty? ris)
       (resolve/resolve-as nil {:message "Invalid content: Not a valid RIS file."})

       (and metadata (empty? json))
       (resolve/resolve-as nil {:message "Invalid metadata: Not valid JSON."
                                :metadata metadata})

       :else
       (let [content (ris/ris-maps->str ris)
             file-hash (-> content .getBytes io/input-stream file/sha3-256)
             data {:metadata json}
             content-hash (-> {:data data
                               :file-hash
                               (.encode (Base64/getEncoder) file-hash)}
                              hasch/edn-hash
                              byte-array)]
         (file/put-entity-content!
          (get-in context [:pedestal :s3])
          {:content (-> content .getBytes io/input-stream)
           :file-hash file-hash})
         (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
           (create-entity-helper!
            context input
            {:content-hash content-hash
             :content-table :content-file
             :data data
             :file-hash file-hash})))))))

(defn dataset-entities-subscription [context {{:keys [datasetId uniqueExternalIds uniqueGroupingIds]} :input} source-stream]
  (ensure-sysrev-dev
   context
   (let [int-id (sl/parse-int-id datasetId)
         q {:select :id
            :from :entity
            :where [:and
                    [:= :dataset-id int-id]
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
               (if-not int-id
                 (source-stream nil)
                 (try
                   (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
                     (reduce
                      (fn [_ row]
                        (source-stream
                         (resolve-dataset-entity context {:id (str (:id row))} nil)))
                      nil
                      (plan context q))
                     (source-stream nil))
                   (catch Exception e
                     (prn e)))))]
     (fn [] (future-cancel fut)))))

