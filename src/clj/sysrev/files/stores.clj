(ns sysrev.files.stores
  (:require [sysrev.files.aws :as aws]
            [sysrev.config.core :refer [env]]
            [sysrev.files.store :as store]
            [clojure.tools.logging :as log]))

(defmulti get-store
  "Based on the settings available in env, get a FileStore"
  (fn [] (get-in env [:filestore :type])))

(defmethod get-store :s3 []
  (-> env
      :filestore
      aws/map->S3Connection
      aws/->S3Store
      aws/connect))

(defmethod get-store :default []
  (log/warn "No filestore specified in configuration. Just logging intent.")
  (reify store/FileStore
    (save-file [this project-id user-id name file]
      (log/warn (str "Fakestore: Storing " name " attached to project " project-id)))
    (make-unique-key [this]
      (log/warn (str "Made a UUID: "(java.util.UUID/randomUUID))))
    (list-files-for-project [this project-id]
      (log/warn (str "No files for project " project-id)))
    (get-file-by-key [this project-id key]
      (log/warn (str "No file key " key " found for project " project-id)))
    (delete-file [this project-id key]
      (log/warn "Delete file " key " for project " project-id))))


(defn store-file [project-id user-id name file]
  (-> (get-store)
      (store/save-file project-id user-id name file)))

(defn project-files [project-id]
  (-> (get-store)
      (store/list-files-for-project project-id)))

(defn delete-file [project-id key]
  (-> (get-store)
      (store/delete-file project-id key)))

(defn get-file [project-id key]
  (-> (get-store)
      (store/get-file-by-key project-id key)))
