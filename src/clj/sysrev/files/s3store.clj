(ns sysrev.files.s3store
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3transfer]
            [sysrev.config.core :refer [env]]
            [sysrev.util :as util]))

(def pdf-bucket "sysrev.pdf")

(defn get-credentials
  []
  (-> env
      :filestore
      (select-keys [:access-key :secret-key :endpoint])))

(defn save-file
  [^java.io.File file & {:keys [bucket-name]
                         :or {bucket-name pdf-bucket}}]
  (let [sha-1-hash (-> file
                       util/file->byte-array
                       util/byte-array->sha-1-hash )]
    (s3/put-object (get-credentials)
                   :bucket-name bucket-name
                   :key sha-1-hash
                   :file file)
    sha-1-hash))

;; list the files
;;(s3/list-objects-v2 (get-credentials) {:bucket-name pdf-bucket})

(defn get-file
  "Given a file-key and optional bucket-name, return a byte array"
  [file-key & {:keys [bucket-name]
               :or {bucket-name pdf-bucket}}]
  (-> (s3/get-object (get-credentials)
                     :bucket-name bucket-name
                     :key file-key)
      :object-content
      (util/slurp-bytes)))

