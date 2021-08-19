(ns sysrev.datapub-client.core
  (:require [clj-http.client :as http]
            [clojure.string :as str]))

(defn return->string [return]
  (cond
    (string? return) return
    (seq return) (->> return
                      (keep #(when % (name %)))
                      (str/join \space))
    :else (throw (ex-info "Should be a string or seq." {:value return}))))

(defn q-dataset-entity [return]
  (str "query($id: PositiveInt!){datasetEntity(id: $id){"
       (return->string return)
       "}}"))

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute! [& {:keys [auth-token endpoint query variables]}]
  (-> (or endpoint "https://www.datapub.dev/api")
      (http/post
       {:as :json
        :content-type :json
        :form-params {:query query :variables variables}
        :headers {"Authorization" (str "Bearer " auth-token)}})
      throw-errors
      :body))

(defn get-dataset-entity [^Long id return & {:keys [auth-token endpoint]}]
  (-> (execute! :query (q-dataset-entity return) :variables {:id id}
                :auth-token auth-token :endpoint endpoint)
      :data :datasetEntity))
