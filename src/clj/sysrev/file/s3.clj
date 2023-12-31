(ns sysrev.file.s3
  "Interface to Amazon S3 for storing files"
  (:require [aws-api-failjure :as aaf]
            [clojure.spec.alpha :as s]
            [cognitect.aws.client.api :as aws]
            [orchestra.core :refer [defn-spec]]
            [sysrev.aws-client.interface :as aws-client]
            [sysrev.config :refer [env]]
            [sysrev.memcached.interface :as mem]
            [sysrev.util :as util :refer [in? opt-keys]]))

(defonce ^:private byte-array-type (type (byte-array 1)))

(s/def ::byte-array #(= (type %) byte-array-type))

(s/def ::file #(= (type %) java.io.File))
(s/def ::file-bytes ::byte-array)
(s/def ::file-key string?)
(s/def ::s3-response any?)

(s/def ::bucket (s/and keyword? (in? #{:pdf :import :image :document})))

(defn-spec sysrev-buckets (s/map-of ::bucket (s/nilable string?))
  []
  {:pdf       "sysrev.pdf"
   :import    "sysrev.imports"
   :image     "sysrev.image"
   :document  (-> env :filestore :bucket-name)})

(defn create-s3-buckets! [s3-client]
  (when (:create? (:filestore env))
    (doseq [name (vals (sysrev-buckets))]
      (aws-client/invoke!
       s3-client
       {:op :CreateBucket
        :request {:Bucket name}})))
  s3-client)

(defn-spec lookup-bucket (s/nilable string?)
  [bucket ::bucket]
  (or (get (sysrev-buckets) bucket)
      (throw (Exception. (str "invalid bucket specifier: " (pr-str bucket))))))

(defn-spec save-byte-array ::file-key
  [sr-context map? file-bytes ::byte-array, bucket ::bucket & {:keys [file-key]} (opt-keys ::file-key)]
  (let [file-key (or (some-> file-key str)
                     (some-> file-bytes util/byte-array->sha-1-hash))]
    (aws-client/invoke!
     (:s3 sr-context)
     {:op :PutObject
      :request {:Body file-bytes
                :Bucket (lookup-bucket bucket)
                :ContentLength (count file-bytes)
                :Key file-key}})
    file-key))

(defn-spec save-file ::file-key
  [{:as sr-context :keys [memcached]} map?
   file any?,
   bucket ::bucket
   & {:keys [file-key]} (opt-keys ::file-key)]
  (let [file-key (save-byte-array
                  sr-context
                  (util/file->byte-array file)
                  bucket
                  (when file-key
                    {:file-key file-key}))]
    (mem/cache-set
     memcached
     (str "sysrev.file.core/s3-key/" bucket "/" file-key)
     86400
     true)
    file-key))

(defn-spec lookup-file ::s3-response
  [sr-context map? file-key ::file-key, bucket ::bucket]
  (let [r (aws/invoke
           (:client (:s3 sr-context))
           {:op :GetObject
            :request {:Bucket (lookup-bucket bucket)
                      :Key (some-> file-key str)}})]
    (if-let [anom (:cognitect.anomalies/category r)]
      (if (= :cognitect.anomalies/not-found anom)
        (when (not= :document bucket)
          (lookup-file sr-context file-key :document))
        (throw (aaf/->ex-info r)))
      r)))

(defn get-file-stream [sr-context file-key bucket]
  (:Body (lookup-file sr-context file-key bucket)))

; s3store doesn't save any bucket information, making it useless
; for existence checks.
(defn-spec s3-key-exists? boolean?
  [{:keys [memcached s3]} map? bucket ::bucket key ::file-key]
  (mem/cache
   memcached
   (str "sysrev.file.core/s3-key/" bucket "/" key)
   86400
   (try
     (aws-client/invoke!
      s3
      {:op :HeadObject
       :request {:Bucket (lookup-bucket bucket)
                 :Key key}})
     true
     (catch Exception e
       (if (-> e ex-data :result :cognitect.anomalies/category
               (= :cognitect.anomalies/not-found))
         false
         (throw e))))))
