(ns sysrev.datapub-client.interface
  (:require [sysrev.datapub-client.core :as datapub-client]))

(defn get-dataset-entity
  "Retrieve a DatasetEntity by its id. return may be a string like
  \"content mediaType\" or a collection of keywords like
  [:content :mediaType]."
  [^Long id return & {:keys [auth-token endpoint]}]
  (datapub-client/get-dataset-entity
   id return
   :auth-token auth-token :endpoint endpoint))
