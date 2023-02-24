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

(defn form-params-request [{:keys [auth-token query variables]}]
  {:as :json
   :content-type :json
   :form-params {:query query :variables variables}
   :headers {"Authorization" (str "Bearer " auth-token)}})

(defn multipart-params-request
  [{:keys [auth-token content content-object-path query variables]}]
  {:as :json
   :headers {"Authorization" (str "Bearer " auth-token)}
   :multipart
   [{:name "operations"
     :content (json/generate-string {:query query :variables variables})}
    {:name "map"
     :content (json/generate-string {"0" content-object-path})}
    {:name "0"
     :content content}]})

(defn execute-request! [{:keys [endpoint request]}]
  (-> (try
        (http/post endpoint request)
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

(defn execute! [& {:as opts}]
  (execute-request!
   (assoc opts :request (form-params-request opts))))

(defn create-dataset! [input return & {:as opts}]
  (-> (execute!
       :query (q/m-create-dataset return)
       :variables {:input input}
       opts)
      :data :createDataset))

(defn create-dataset-entity! [input return & {:keys [auth-token endpoint]}]
  (-> (execute-request!
       {:endpoint endpoint
        :request
        (if (:contentUpload input)
          (multipart-params-request
           {:auth-token auth-token
            :content (:contentUpload input)
            :content-object-path ["variables.input.contentUpload"]
            :query (q/m-create-dataset-entity return)
            :variables {:input (dissoc input :contentUpload)}})
          (form-params-request
           {:auth-token auth-token
            :query (q/m-create-dataset-entity return)
            :variables {:input input}}))})
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
          (recur (conj! acc payload))
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
