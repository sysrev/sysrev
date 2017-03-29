(ns sysrev.files.stores
  (:require [sysrev.files.aws :as aws]
            [sysrev.config.core :refer [env]]
            [sysrev.files.store :as store]))

(defmulti get-store (fn [] (get-in env [:filestore :type])))

(defmethod get-store :s3 []
  (-> env
      :filestore
      aws/map->S3Connection
      aws/->S3Store
      aws/connect))

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
