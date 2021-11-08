(ns datapub.file
  (:require [aws-api-failjure :as aaf]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [com.stuartsierra.component :as component])
  (:import java.io.InputStream
           java.nio.file.Path
           java.security.MessageDigest
           java.util.Base64))

(set! *warn-on-reflection* true)

(defn sha3-256 ^bytes [^InputStream in]
  (let [md (MessageDigest/getInstance "SHA3-256")
        buffer (byte-array 8192)]
    (loop []
      (let [bytes-read (.read in buffer)]
        (if (pos? bytes-read)
          (do
            (.update md buffer 0 bytes-read)
            (recur))
          (.digest md))))))

(defn file-sha3-256 ^bytes [^Path path]
  (-> path .toUri .toURL .openStream sha3-256))

(defrecord AwsClient [after-start client client-opts]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [creds (select-keys client-opts [:access-key-id :secret-access-key])
            client-opts (if (some seq (vals creds))
                          (assoc client-opts :credentials-provider
                                 (credentials/basic-credentials-provider creds))
                          client-opts)
            client-opts (select-keys client-opts [:api :credentials-provider :endpoint-override])
            this (assoc this :client (aws/client client-opts))]
        (if after-start
          (after-start this)
          this))))
  (stop [this]
    (when client
      (aws/stop client))
    (assoc this :client nil)))

(defn aws-client [{:as m :keys [after-start client-opts]}]
  (map->AwsClient m))

(defn invoke! [client op-map]
  (aaf/throwing-invoke (:client client client) op-map))

(defn s3-client [client-opts]
  (aws-client
   {:after-start
    (fn [aws-client]
      (let [{:keys [create? name]}
            #__ (get-in aws-client [:config :s3 :datapub-bucket])]
        (when create?
          (invoke! (:client aws-client)
                   {:op :CreateBucket
                    :request {:Bucket name}}))
        aws-client))
    :client-opts (assoc client-opts :api :s3)}))

(defn bucket-name [s3]
  (get-in s3 [:config :s3 :datapub-bucket :name]))

(defn content-key [^bytes file-hash]
  (-> (.encodeToString (Base64/getUrlEncoder) file-hash)
      (str/replace "=" "")
      (->> (str "entity-content/"))))

(defn entity-content-head
  "Returns the entity HEAD data from S3, or nil if it does not exist.

  The return value looks like this:
  {:ContentLength 3,
   :ContentType \"application/octet-stream\",
   :ETag \"\\\"e1faffb3e614e6c2fba74296962386b7\\\"\",
   :LastModified #inst \"2021-09-17T22:38:11.000-00:00\"
   :Metadata {}}"
  [s3 ^bytes file-hash]
  (let [r (aws/invoke
           (:client s3)
           {:op :HeadObject
            :request {:Bucket (bucket-name s3)
                      :Key (content-key file-hash)}})]
    (if-let [anom (:cognitect.anomalies/category r)]
      (when-not (= :cognitect.anomalies/not-found)
        (throw (aaf/->ex-info r)))
      r)))

(defn get-entity-content
  "GETs the entity content from S3, or returns nil if it does not exist.

  The return value looks like this:
  {:AcceptRanges \"bytes\",
   :Body #object[java.io.BufferedInputStream ...],
   :ContentLanguage \"en-US\",
   :ContentLength 3,
   :ContentType \"application/octet-stream\",
   :ETag \"\\\"e1faffb3e614e6c2fba74296962386b7\\\"\",
   :LastModified #inst 2021-09-17T23:14:34.000-00:00,
   :Metadata {}}"
  [s3 ^bytes file-hash]
  (let [r (aws/invoke
           (:client s3)
           {:op :GetObject
            :request {:Bucket (bucket-name s3)
                      :Key (content-key file-hash)}})]
    (if-let [anom (:cognitect.anomalies/category r)]
      (when-not (= :cognitect.anomalies/not-found)
        (throw (aaf/->ex-info r)))
      r)))

(defn put-entity-content!
  "PUTs the entity content to S3 and returns a map like this:
  {:ETag \"\\\"e1faffb3e614e6c2fba74296962386b7\\\"\"}"
  [s3 {:keys [^bytes content ^bytes file-hash]}]
  (or
   (some-> (entity-content-head s3 file-hash)
           (select-keys [:ETag]))
   (invoke!
    s3
    {:op :PutObject
     :request {:Body content
               :Bucket (bucket-name s3)
               :Key (content-key file-hash)}})))
