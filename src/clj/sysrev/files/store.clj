(ns sysrev.files.store
  (:require [sysrev.config.core :refer [env]]))

(defrecord FileResponse [filerec filestream])

(defprotocol FileStore
  (save-file [this project-id user-id name file]
    "Save file should save the file, link to project, and
    return a map of file metadata")
  (make-unique-key [this]
    "Create some uuid used by this fs")
  (list-files-for-project [this project-id]
    "Get a sorted map by ordering of name, awskey")
  (get-file-by-key [this project-id key]
    "Retrieve a java File indexed by key, nil if not exists")
  (delete-file [this project-id key]
    "Delete a file (might not actually delete the file in the fs)"))
