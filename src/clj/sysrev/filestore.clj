(ns sysrev.filestore
  (:require [amazonica.aws.s3 :as s3]
            [sysrev.config.core :as config]
            [sysrev.db.files :as files]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in?]])
  (:import (java.io ByteArrayInputStream)
           java.util.UUID))

;; to create a new bucket:
;; (s3/create-bucket (s3-credentials) <bucket-name>)
;; to list contents of bucket:
;; (:object-summaries (s3/list-objects (s3-credentials) (lookup-bucket <bucket-keyword>)))
;; empty a bucket (note: you are limited to 1000 keys / transaction
;; (let [bucket-name <bucket-keyword>] (map #(delete-file (:key %) bucket-name) (:object-summaries (s3/list-objects (s3-credentials) (lookup-bucket bucket-name)))))
(defn sysrev-buckets []
  {:pdf       "sysrev.pdf"
   :import    "sysrev.imports"
   :image     "sysrev.image"
   :document  (-> config/env :filestore :bucket-name)})

(defn lookup-bucket [bucket]
  (or (get (sysrev-buckets) bucket)
      (throw (Exception. (str "invalid bucket specifier: " (pr-str bucket))))))

(defn s3-credentials []
  (-> config/env :filestore
      (select-keys [:access-key :secret-key :endpoint])))

(defn save-file [file bucket & {:keys [file-key]}]
  (let [bucket-name (lookup-bucket bucket)
        file-key (or (some-> file-key str)
                     (some-> file util/file->sha-1-hash))]
    (s3/put-object (s3-credentials)
                   :bucket-name bucket-name
                   :key file-key
                   :file file)
    file-key))

(defn delete-file [key bucket]
  (let [bucket-name (lookup-bucket bucket)]
    (s3/delete-object (s3-credentials)
                      :bucket-name bucket-name
                      :key key)))

(defn save-byte-array
  [byte-array bucket & {:keys [file-key]}]
  (let [bucket-name (lookup-bucket bucket)
        file-key (or (some-> file-key str)
                     (util/byte-array->sha-1-hash byte-array))
        byte-array-input-stream (ByteArrayInputStream. byte-array)]
    (s3/put-object (s3-credentials)
                   :bucket-name bucket-name
                   :key file-key
                   :input-stream byte-array-input-stream
                   :metadata {:content-length (count byte-array)})
    file-key))

(defn lookup-file
  "Given a file-key and bucket specifier, return s3 object map"
  [file-key bucket]
  (s3/get-object (s3-credentials)
                 :bucket-name (lookup-bucket bucket)
                 :key (some-> file-key str)))

(defn get-file-stream
  "Return input stream of file data for file-key in bucket."
  [file-key bucket]
  (:object-content (lookup-file file-key bucket)))

(defn get-file-bytes
  "Return byte array of file data for file-key in bucket."
  [file-key bucket]
  (util/slurp-bytes (get-file-stream file-key bucket)))

;; TODO: move this somewhere else?
(defn save-document-file [project-id user-id name file]
  (let [file-key (UUID/randomUUID)]
    (save-file file :document :file-key file-key)
    (-> (lookup-file file-key :document)
        (select-keys [:etag :content-md5])
        (merge {:project-id project-id
                :name name
                :file-id file-key
                :user-id user-id})
        files/map->Filerec
        files/insert-document-file-rec)))
