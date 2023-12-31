(ns sysrev.datapub-client.interface
  (:require [sysrev.datapub-client.core :as core]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn consume-subscription!
  "Return a vector of responses matching the subscription query. The
  subscription must terminate for this to return."
  [& {:keys [auth-token endpoint query variables]}]
  (core/consume-subscription!
   :auth-token auth-token
   :endpoint endpoint
   :query query
   :variables variables))

(defn create-dataset!
  "Create a Dataset."
  [input return & {:as opts}]
  (core/create-dataset! input return opts))

(defn create-dataset-entity!
  "Create a DatasetEntity.

  (:contentUpload input) may be a String, InputStream, File, or a byte-array."
  [input return & {:keys [auth-token endpoint]}]
  (core/create-dataset-entity!
   input return
   :auth-token auth-token :endpoint endpoint))

#_:clj-kondo/ignore
(defn execute!
  "Executes a GraphQL query and returns the result. Throws an
  ExceptionInfo when the endpoint returns an error response."
  [{:keys [auth-token endpoint query variables] :as args}]
  (apply core/execute! (mapcat identity args)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn get-dataset
  "Retrieve a Dataset by its id."
  [^Long id return & {:keys [auth-token endpoint]}]
  (core/get-dataset
   id return
   :auth-token auth-token :endpoint endpoint))

(defn get-dataset-entity
  "Retrieve a DatasetEntity by its id. return may be a string like
  \"content mediaType\" or a collection of keywords like
  [:content :mediaType]."
  [^Long id return & {:keys [auth-token endpoint]}]
  (core/get-dataset-entity
   id return
   :auth-token auth-token :endpoint endpoint))

(defn search-dataset
  "Return a vector of DatasetEntity maps matching the search input."
  [input return & {:keys [auth-token endpoint]}]
  (core/search-dataset
   input return
   :auth-token auth-token :endpoint endpoint))
