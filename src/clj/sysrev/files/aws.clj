(ns sysrev.files.aws
  (:require [amazonica.core :refer [defcredential]]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer]
            [sysrev.files.store :as store]
            [sysrev.db.files :as files :refer [insert-file-rec]])
  (:import java.util.UUID))

(defprotocol S3Connects
  (connect [this]))

(defprotocol S3Attributes
  (attributes [this]))

(defrecord S3Connection [access-key secret-key endpoint bucket-name]
  S3Attributes
  (attributes [this] [access-key secret-key endpoint]))


(defrecord FileResponse [filerec filestream])

(deftype S3Store [connection]
  S3Connects
  (connect [this]
    (apply defcredential (attributes connection))
    this)

  store/FileStore
  (make-unique-key [this] (UUID/randomUUID))

  (save-file [this project-id user-id name file]
    (let [uuid (store/make-unique-key this)
          save-req
            {:file file
             :bucket-name (:bucket-name connection)
             :key (str uuid)}
          detail {:project-id project-id
                  :name name
                  :file-id uuid
                  :user-id user-id}]
      (-> save-req
          (s3/put-object)
          (select-keys [:etag :content-md5])
          (merge detail)
          (files/map->Filerec)
          (insert-file-rec))))

  (list-files-for-project [this project-id]
    (files/list-files-for-project project-id))

  (get-file-by-key [this project-id key]
    (let [req {:key key :bucket-name (:bucket-name connection)}]
      (->FileResponse
        (files/file-by-id key project-id)
        (-> (s3/get-object req)
            :object-content))))

  (delete-file [this project-id key]
    (files/mark-deleted key project-id)))

