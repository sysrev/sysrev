(ns sysrev.datapub-client.interface
  (:require [sysrev.datapub-client.core :as datapub-client]))

(defn consume-subscription!
  "Return a vector of responses matching the subscription query. The
  subscription must terminate for this to return."
  [& {:keys [auth-token endpoint query variables]}]
  (datapub-client/consume-subscription!
   :auth-token auth-token
   :endpoint endpoint
   :query query
   :variables variables))

(defn get-dataset-entity
  "Retrieve a DatasetEntity by its id. return may be a string like
  \"content mediaType\" or a collection of keywords like
  [:content :mediaType]."
  [^Long id return & {:keys [auth-token endpoint]}]
  (datapub-client/get-dataset-entity
   id return
   :auth-token auth-token :endpoint endpoint))

(defn search-dataset
  "Return a vector of DatasetEntity maps matching the search input."
  [input return & {:keys [auth-token endpoint]}]
  (datapub-client/search-dataset
   input return
   :auth-token auth-token :endpoint endpoint))
