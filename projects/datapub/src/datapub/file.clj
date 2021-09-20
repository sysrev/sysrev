(ns datapub.file
  (:require [aws-api-failjure :as aaf]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [com.stuartsierra.component :as component])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn ^Path create-temp-file! [suffix]
  (Files/createTempFile "datapub-" suffix (make-array FileAttribute 0)))

(defmacro with-temp-file
  "Creates a file and binds name-sym to its Path."
  [[name-sym {:keys [suffix]}] & body]
  `(let [~name-sym (create-temp-file! ~suffix)]
     (try
       ~@body
       (finally
         (Files/delete ~name-sym)))))

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

(defn content-key [^bytes content-hash]
  (-> (.encodeToString (Base64/getUrlEncoder) content-hash)
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
  [s3 ^bytes content-hash]
  (let [r (aws/invoke
           (:client s3)
           {:op :HeadObject
            :request {:Bucket (bucket-name s3)
                      :Key (content-key content-hash)}})]
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
  [s3 ^bytes content-hash]
  (let [r (aws/invoke
           (:client s3)
           {:op :GetObject
            :request {:Bucket (bucket-name s3)
                      :Key (content-key content-hash)}})]
    (if-let [anom (:cognitect.anomalies/category r)]
      (when-not (= :cognitect.anomalies/not-found)
        (throw (aaf/->ex-info r)))
      r)))

(defn put-entity-content!
  "PUTs the entity content to S3 and returns a map like this:
  {:ETag \"\\\"e1faffb3e614e6c2fba74296962386b7\\\"\"}"
  [s3 {:keys [^bytes content ^bytes content-hash]}]
  (or
   (some-> (entity-content-head s3 content-hash)
           (select-keys [:ETag]))
   (invoke!
    s3
    {:op :PutObject
     :request {:Body content
               :Bucket (bucket-name s3)
               :Key (content-key content-hash)}})))
