(ns sysrev.datapub-client.core
  (:require [aleph.http :as ahttp]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [manifold.stream :as stream]))

(defn return->string [return]
  (cond
    (string? return) return
    (seq return) (->> return
                      (keep #(when % (name %)))
                      (str/join \space))
    :else (throw (ex-info "Should be a string or seq." {:value return}))))

(defn m-create-dataset-entity [return]
  (str "mutation($datasetId: PositiveInt!, $content: String!, $externalId: String, $mediaType: String, $metadata: String) {
     createDatasetEntity(datasetId: $datasetId, content: $content, mediaType: $mediaType, externalId: $externalId, metadata: $metadata){"
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

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute! [& {:keys [auth-token endpoint query variables]}]
  (-> (http/post
       endpoint
       {:as :json
        :content-type :json
        :form-params {:query query :variables variables}
        :headers {"Authorization" (str "Bearer " auth-token)}})
      throw-errors
      :body))

(defn create-dataset-entity! [input return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (m-create-dataset-entity return) :variables input
                :auth-token auth-token :endpoint endpoint)
      :data :createDatasetEntity))

(defn get-dataset [^Long id return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (q-dataset return) :variables {:id id}
                :auth-token auth-token :endpoint endpoint)
      :data :dataset))

(defn get-dataset-entity [^Long id return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (q-dataset-entity return) :variables {:id id}
                :auth-token auth-token :endpoint endpoint)
      :data :datasetEntity))

(defn consume-subscription! [& {:keys [auth-token endpoint query variables]}]
  (with-open [conn @(ahttp/websocket-client
                     endpoint
                     {:sub-protocols "graphql-ws"})]
    (stream/put! conn (json/generate-string {:type "connection_init" :payload {}}))
    (loop [acc (transient [])]
      (let [{:keys [payload type]} (json/parse-string @(stream/take! conn) keyword)]
        (case type
          "complete" (persistent! acc)
          "connection_ack"
          (do
            (stream/put! conn (json/generate-string {:id "1"
                                                     :type "start"
                                                     :payload
                                                     {:query query
                                                      :variables variables}}))
            (recur acc))
          "data"
          (do
            (conj! acc payload)
            (recur acc))
          "error"
          (throw (ex-info (str "Error in GraphQL subscription: " (:message payload))
                          {:error payload})))))))

(defn search-dataset [input return & {:keys [auth-token endpoint]}]
  (mapv
   (comp :searchDataset :data)
   (consume-subscription!
    :auth-token auth-token
    :endpoint endpoint
    :query (s-search-dataset return)
    :variables {:input input})))
