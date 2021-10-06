(ns datapub.test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia.constants :as constants]
            [com.walmartlabs.lacinia.parser :as parser]
            [com.walmartlabs.lacinia.selection :as selection]
            [datapub.main :as main]
            [io.pedestal.test :as test])
  (:import (java.util Base64)
           (org.apache.commons.io IOUtils)))

(def create-dataset
  "mutation($input: CreateDatasetInput!){createDataset(input: $input){id}}")

(def create-dataset-entity
  "mutation($datasetId: PositiveInt!, $content: String!, $externalId: String, $mediaType: String, $metadata: String) {
     createDatasetEntity(datasetId: $datasetId, content: $content, mediaType: $mediaType, externalId: $externalId, metadata: $metadata){id content externalId mediaType metadata}
  }")

(def create-dataset-index
  "mutation($datasetId: PositiveInt!, $path: String!, $type: DatasetIndexType!){createDatasetIndex(datasetId: $datasetId, path: $path, type: $type){path type}}")

(def create-json-dataset-entity
  "mutation($datasetId: PositiveInt!, $content: String!, $externalId: String) {
     createDatasetEntity(datasetId: $datasetId, content: $content, mediaType: \"application/json\", externalId: $externalId){id content externalId mediaType}
  }")

(def dataset-entity
  "query($id: PositiveInt!) {datasetEntity(id: $id){id content externalId mediaType metadata}}")

(def subscribe-dataset-entities
  "subscription($id: PositiveInt!, $uniqueExternalIds: Boolean){datasetEntities(datasetId: $id, uniqueExternalIds: $uniqueExternalIds){content externalId mediaType}}")

(def subscribe-search-dataset
  "subscription($input: SearchDatasetInput!){searchDataset(input: $input){content externalId id mediaType}}")

(defn response-for [system verb url & options]
  (apply
   test/response-for (get-in system [:pedestal :service :io.pedestal.http/service-fn])
   verb url options))

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute [system query & [variables]]
  (let [sysrev-dev-key (-> system :pedestal :opts :sysrev-dev-key)]
    (-> (response-for system
                      :post "/api"
                      :headers {"Authorization" (str "Bearer " sysrev-dev-key)
                                "Content-Type" "application/json"}
                      :body (json/generate-string {:query query
                                                   :variables variables}))
        (update :body json/parse-string true))))

(defn query-arguments [query]
  (-> query :selections first selection/arguments))

(defn execute-subscription [system f query & [variables opts]]
  (let [sysrev-dev-key (-> system :pedestal :opts :sysrev-dev-key)
        {:keys [timeout-ms]
         :or {timeout-ms 30000}} opts
        schema (-> system :pedestal :service-map :graphql-schema)
        prepared-query (parser/prepare-with-query-variables
                        (parser/parse-query schema query)
                        variables)
        context {:com.walmartlabs.lacinia/connection-params
                 {:authorization (str "Bearer " sysrev-dev-key)}
                 :config (:config system)
                 :pedestal (:pedestal system)
                 constants/parsed-query-key prepared-query}
        results (atom [])
        done (promise)
        source-stream #(if (nil? %)
                         (do (deliver done true) nil)
                         (swap! results conj %))
        close-streamer (f context (query-arguments prepared-query)
                          source-stream)
        fut (future
              (Thread/sleep timeout-ms)
              (deliver done false))
        d @done]
    (close-streamer)
    (if d
      @results
      (throw (RuntimeException. "Streamer exceeded timeout")))))

(defmacro with-test-system [[name-sym options] & body]
  `(let [options# ~options
         system# (-> (main/get-config)
                     (assoc :env (:env options# :test))
                     (update :pedestal dissoc :port)
                     (update :postgres assoc :embedded? true :host "localhost"
                             :port 0 :user "postgres" :password nil)
                     ((:get-system-map options# main/system-map))
                     component/start)
         ~name-sym system#]
     (try
       ~@body
       (finally
         (component/stop system#)))))

(def ctgov-indices
  [[:TEXT ["ProtocolSection" "ConditionsModule" "ConditionList" "Condition" :*]]
   [:TEXT ["ProtocolSection" "DescriptionModule" "BriefSummary"]]
   [:TEXT ["ProtocolSection" "DescriptionModule" "DetailedDescription"]]
   [:TEXT ["ProtocolSection" "IdentificationModule" "BriefTitle"]]
   [:TEXT ["ProtocolSection" "IdentificationModule" "OfficialTitle"]]
   [:TEXT ["ProtocolSection" "ArmsInterventionsModule" "InterventionList" "Intervention" :* "InterventionName"]]])

(defn load-ctgov-dataset! [system]
  (let [ds-id (-> system
                  (execute
                   create-dataset
                   {:input
                    {:description "ClinicalTrials.gov is a database of privately and publicly funded clinical studies conducted around the world."
                     :name "ClinicalTrials.gov"
                     :public true}})
                  :body
                  throw-errors
                  (get-in [:data :createDataset :id]))]
    (doseq [{:keys [content externalId]} (-> "datapub/ctgov-entities.edn"
                                             io/resource
                                             slurp
                                             edn/read-string)]
      (throw-errors
       (execute
        system create-json-dataset-entity
        {:content (json/generate-string content)
         :datasetId ds-id
         :externalId externalId})))
    (doseq [[type path] ctgov-indices]
      (throw-errors
       (execute
        system create-dataset-index
        {:datasetId ds-id
         :path (pr-str path)
         :type (name type)})))
    ds-id))

(def fda-drugs-docs-indices
  [[:TEXT ["text"]]])

(defn load-fda-drugs-docs-dataset! [system]
  (let [ds-id (-> system
                  (execute
                   create-dataset
                   {:input
                    {:name "Drugs@FDA Application Documents"
                     :public true}})
                  :body
                  throw-errors
                  (get-in [:data :createDataset :id]))]
    (doseq [{:keys [external-id filename metadata]}
            #__ (-> "datapub/fda-drugs-docs-entities.edn"
                    io/resource
                    slurp
                    edn/read-string)]
      (throw-errors
       (execute
        system create-dataset-entity
        {:content (->> (str "datapub/file-uploads/" filename)
                       io/resource
                       .openStream
                       IOUtils/toByteArray
                       (.encodeToString (Base64/getEncoder)))
         :datasetId ds-id
         :externalId external-id
         :mediaType "application/pdf"
         :metadata (json/generate-string metadata)})))
    (doseq [[type path] fda-drugs-docs-indices]
      (throw-errors
       (execute
        system create-dataset-index
        {:datasetId ds-id
         :path (pr-str path)
         :type (name type)})))
    ds-id))

(defn load-all-fixtures! [system]
  (load-ctgov-dataset! system)
  (load-fda-drugs-docs-dataset! system)
  nil)
