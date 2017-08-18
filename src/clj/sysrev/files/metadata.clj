(ns sysrev.files.metadata
  (:require [sysrev.files.store :as store]
            [sysrev.db.files :as files :refer [insert-file-rec]])
  (:import java.util.UUID))

(deftype Metadata []
  store/FileStore
  (make-unique-key [this] (UUID/randomUUID))

  (save-file [this project-id user-id name file]
    (let [uuid (store/make-unique-key this)
          save-req
          {:file file
           :key (str uuid)}
          detail {:project-id project-id
                  :name name
                  :file-id uuid
                  :user-id user-id}]
      (-> save-req
          (merge detail)
          (files/map->Filerec)
          (insert-file-rec))))

  (list-files-for-project [this project-id]
    (files/list-files-for-project project-id))

  (get-file-by-key [this project-id key]
    (store/->FileResponse
      (files/file-by-id key project-id)
      nil))

  (delete-file [this project-id key]
    (files/mark-deleted key project-id)))

