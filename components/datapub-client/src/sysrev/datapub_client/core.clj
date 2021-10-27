(ns sysrev.datapub-client.core
  (:require [aleph.http :as ahttp]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [manifold.stream :as stream]
            [sysrev.datapub-client.queries :as q])
  (:import clojure.lang.ExceptionInfo))

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute! [& {:keys [auth-token endpoint query variables]}]
  (-> (try
        (http/post
         endpoint
         {:as :json
          :content-type :json
          :form-params {:query query :variables variables}
          :headers {"Authorization" (str "Bearer " auth-token)}})
        (catch ExceptionInfo e
          (let [{:keys [body status]} (ex-data e)]
            (if (not= 400 status)
              (throw e)
              (let [parsed-body (try
                                  (json/parse-string body keyword)
                                  (catch Exception e
                                    e))]
                (if (instance? Exception parsed-body)
                  (throw
                   (ex-info (str "Exception while parsing response body as JSON: "
                                 (.getMessage e))
                            {:body body
                             :response (ex-data e)}
                            e))
                  (throw
                   (ex-info (->> parsed-body :errors
                                 (map (comp pr-str :message))
                                 (str/join ", ")
                                 (str "GraphQL errors: "))
                            {:errors (:errors parsed-body)
                             :response (ex-data e)}
                            e))))))))
      throw-errors
      :body))

(defn create-dataset-entity! [input return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (q/m-create-dataset-entity return) :variables {:input input}
                :auth-token auth-token :endpoint endpoint)
      :data :createDatasetEntity))

(defn get-dataset [^Long id return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (q/q-dataset return) :variables {:id id}
                :auth-token auth-token :endpoint endpoint)
      :data :dataset))

(defn get-dataset-entity [^Long id return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (q/q-dataset-entity return) :variables {:id id}
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
                          {:error payload}))
          "ka" (recur acc))))))

(defn search-dataset [input return & {:keys [auth-token endpoint]}]
  (mapv
   (comp :searchDataset :data)
   (consume-subscription!
    :auth-token auth-token
    :endpoint endpoint
    :query (q/s-search-dataset return)
    :variables {:input input})))
