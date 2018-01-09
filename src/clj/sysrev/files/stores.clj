(ns sysrev.files.stores
  (:require [sysrev.files.aws :as aws]
            [sysrev.config.core :refer [env]]
            [sysrev.files.store :as store]
            [sysrev.files.metadata :as metadata]
            [clojure.tools.logging :as log]
            [sysrev.config.core :refer [env]]))

(defmulti get-store
  "Based on the settings available in env, get a FileStore"
  (fn [] (get-in env [:filestore :type])))

(defmethod get-store :s3 []
  (-> env
      :filestore
      aws/map->S3Connection
      aws/->S3Store
      aws/connect))

(defonce log-stub-calls
  (atom (= (:profile env) :prod)))
(defmacro ^:private log-stub [& args]
  `(when @log-stub-calls
     (log/warn ~@args)))

(defmethod get-store :default []
  (log-stub "No filestore specified in configuration. Using metadata-only.")
  (metadata/->Metadata))


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
