(ns sysrev.file.s3
  "Interface to Amazon S3 for storing files"
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [amazonica.aws.s3 :as s3]
            [sysrev.config.core :refer [env]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? opt-keys]])
  (:import (java.io File ByteArrayInputStream)
           java.util.UUID))

;; for clj-kondo
(declare s3-credentials lookup-bucket lookup-file)

(defonce ^:private byte-array-type (type (byte-array 1)))

(s/def ::byte-array #(= (type %) byte-array-type))

(s/def ::access-key string?)
(s/def ::secret-key string?)
(s/def ::endpoint string?)
(s/def ::credentials (s/keys :req-un [::access-key ::secret-key ::endpoint]))

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
   :document  #_ "sysrev.us" (-> env :filestore :bucket-name)})

(defn-spec lookup-bucket (s/nilable string?)
  [bucket ::bucket]
  (or (get (sysrev-buckets) bucket)
      (throw (Exception. (str "invalid bucket specifier: " (pr-str bucket))))))

(defn-spec s3-credentials ::credentials []
  (-> (:filestore env)
      (select-keys [:access-key :secret-key :endpoint])))

(defn ^:repl create-bucket [name]
  (s3/create-bucket (s3-credentials) name))

(defn ^:repl list-bucket-files [bucket]
  (:object-summaries (s3/list-objects (s3-credentials) (lookup-bucket bucket))))

(defn-spec save-file ::file-key
  [file any?, bucket ::bucket & {:keys [file-key]} (opt-keys ::file-key)]
  (let [file-key (or (some-> file-key str)
                     (some-> file util/file->sha-1-hash))]
    (s3/put-object (s3-credentials) :bucket-name (lookup-bucket bucket)
                   :key file-key :file file)
    file-key))

(defn-spec save-byte-array ::file-key
  [file-bytes ::byte-array, bucket ::bucket & {:keys [file-key]} (opt-keys ::file-key)]
  (let [file-key (or (some-> file-key str)
                     (some-> file-bytes util/byte-array->sha-1-hash))]
    (s3/put-object (s3-credentials) :bucket-name (lookup-bucket bucket)
                   :key file-key
                   :input-stream (ByteArrayInputStream. file-bytes)
                   :metadata {:content-length (count file-bytes)})
    file-key))

(defn-spec delete-file ::s3-response
  [file-key ::file-key, bucket ::bucket]
  (s3/delete-object (s3-credentials) :bucket-name (lookup-bucket bucket)
                    :key file-key))

(defn-spec lookup-file ::s3-response
  [file-key ::file-key, bucket ::bucket]
  (s3/get-object (s3-credentials) :bucket-name (lookup-bucket bucket)
                 :key (some-> file-key str)))

(defn get-file-stream [file-key bucket]
  (:object-content (lookup-file file-key bucket)))

(defn-spec get-file-bytes ::byte-array
  [file-key ::file-key, bucket ::bucket]
  (util/slurp-bytes (get-file-stream file-key bucket)))
