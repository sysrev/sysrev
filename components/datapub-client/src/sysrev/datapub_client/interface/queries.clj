(ns sysrev.datapub-client.interface.queries
  (:require [sysrev.datapub-client.queries :as q]))

(defn q-dataset
  "Returns the string representation of a dataset query.

  The return arg is processed by `return->string`."
  [return]
  (q/q-dataset return))

(defn m-create-dataset-entity
  "Returns the string representation of a createDatasetEntity mutation.

  The return arg is processed by `return->string`."
  [return]
  (q/m-create-dataset-entity return))

(defn m-update-dataset
  "Returns the string representation of an updateDataset mutation.

  The return arg is processed by `return->string`."
  [return]
  (q/m-update-dataset return))

(defn return->string
  "Returns a string representation of the GraphQL return field names for a
  given string or seq. A string argument is returned unchanged.

  Examples:
  (return->string \"id\") => \"id\"
  (return->string [:id :name]) => \"id name\""
  [return]
  (q/return->string return))
