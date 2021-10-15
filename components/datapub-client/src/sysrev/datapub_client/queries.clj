(ns sysrev.datapub-client.queries
  (:require [clojure.string :as str]))

(defn return->string [return]
  (cond
    (string? return) return
    (seq return) (->> return
                      (keep #(when % (name %)))
                      (str/join \space))
    :else (throw (ex-info "Should be a string or seq." {:value return}))))

(defn m-create-dataset-entity [return]
  (str "mutation($datasetId: PositiveInt!, $content: String!, $externalCreated: DateTime, $externalId: String, $mediaType: String, $metadata: String) {
     createDatasetEntity(datasetId: $datasetId, content: $content, mediaType: $mediaType, externalCreated: $externalCreated, externalId: $externalId, metadata: $metadata){"
       (return->string return)
       "}}"))

(defn m-update-dataset [return]
  (str "mutation($input: UpdateDatasetInput){updateDataset(input: $input){"
       (return->string return)
       "}}"))

(defn q-dataset [return]
  (str "query($id: PositiveInt!){dataset(id: $id){"
       (return->string return)
       "}}"))

(defn q-dataset-entity [return]
  (str "query($id: PositiveInt!){datasetEntity(id: $id){"
       (return->string return)
       "}}"))

(defn s-search-dataset [return]
  (str "subscription ($input: SearchDatasetInput!){searchDataset(input: $input){"
       (return->string return)
       "}}"))
