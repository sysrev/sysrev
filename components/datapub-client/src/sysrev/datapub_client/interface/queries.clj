(ns sysrev.datapub-client.interface.queries
  (:require [sysrev.datapub-client.queries :as q]))

(defn m-create-dataset
  "Returns the string representation of a createDataset mutation.

  The return arg is processed by `return->string`."
  [return]
  (q/m-create-dataset return))

(defn m-create-dataset-entity
  "Returns the string representation of a createDatasetEntity mutation.

  The return arg is processed by `return->string`."
  [return]
  (q/m-create-dataset-entity return))

(defn m-create-dataset-index
  "Returns the string representation of a createDatasetIndex mutation.

  The return arg is processed by `return->string`."
  [return]
  (q/m-create-dataset-index return))

(defn m-update-dataset
  "Returns the string representation of an updateDataset mutation.

  The return arg is processed by `return->string`."
  [return]
  (q/m-update-dataset return))

(defn q-dataset
  "Returns the string representation of a dataset query.

  The return arg is processed by `return->string`."
  [return]
  (q/q-dataset return))

(defn q-dataset-entity
  "Returns the string representation of a datasetEntity query.

  The return arg is processed by `return->string`."
  [return]
  (q/q-dataset-entity return))

(defn q-dataset#entities
  "Returns the string representation of a query for the entities field of a dataset.

  The return arg is processed by `return->string`."
  [return]
  (q/q-dataset#entities return))

(defn q-list-datasets
  "Returns the string representation of a listDatasets query.

  The return arg is processed by `return->string`."
  [return]
  (q/q-list-datasets return))

(defn return->string
  "Returns a string representation of the GraphQL return field names for a
  given string or seq. A string argument is returned unchanged.

  Examples:
  (return->string \"id\") => \"id\"
  (return->string [:id :name]) => \"id name\""
  [return]
  (q/return->string return))

(defn s-dataset-entities
  "Returns the string representation of a datasetEntities subscription.

  The return arg is processed by `return->string`."
  [return]
  (q/s-dataset-entities return))

(defn s-search-dataset
  "Returns the string representation of a searchDataset subscription.

  The return arg is processed by `return->string`."
  [return]
  (q/s-search-dataset return))
