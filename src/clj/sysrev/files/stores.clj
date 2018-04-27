(ns sysrev.files.stores
  (:require [clojure.tools.logging :as log]
            [sysrev.config.core :refer [env]]
            [sysrev.files.aws :as aws]
            [sysrev.files.store :as store]
            [sysrev.files.metadata :as metadata]
            [sysrev.db.core :refer [clear-project-cache]]))

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

(defn project-files [project-id]
  (-> (get-store)
      (store/list-files-for-project project-id)))

(defn get-file [project-id key]
  (-> (get-store)
      (store/get-file-by-key project-id key)))

(defn store-file [project-id user-id name file]
  (try
    (-> (get-store)
        (store/save-file project-id user-id name file))
    (finally
      (clear-project-cache project-id))))

(defn delete-file [project-id key]
  (try
    (-> (get-store)
        (store/delete-file project-id key))
    (finally
      (clear-project-cache project-id))))
