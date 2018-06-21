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

(defn save-byte-array
  [byte-array & {:keys [bucket-name]
                   :or {bucket-name pdf-bucket}}]
  (let [sha-1-hash (util/byte-array->sha-1-hash byte-array)
        byte-array-input-stream (java.io.ByteArrayInputStream. byte-array)]
    (s3/put-object (get-credentials)
                   :bucket-name bucket-name
                   :key sha-1-hash
                   :input-stream byte-array-input-stream
                   :metadata {:content-length (count byte-array)})
    sha-1-hash))
;; list the files
;;(s3/list-objects-v2 (get-credentials) {:bucket-name pdf-bucket})

;; delete files
;; (amazonica.aws.s3/delete-objects (sysrev.files.s3store/get-credentials)
;; :bucket-name "sysrev.pdf"
;; :keys (-> (select :key)
;;           (from :s3store)
;;           (where [:not= :filename "PMC4605818.pdf"])
;;           do-query
;;           (into [])
;;           (->> (mapv :key))))
(defn get-file
  "Given a file-key and optional bucket-name, return a byte array"
  [file-key & {:keys [bucket-name]
               :or {bucket-name pdf-bucket}}]
  (-> (s3/get-object (get-credentials)
                     :bucket-name bucket-name
                     :key file-key)
      :object-content
      (util/slurp-bytes)))

