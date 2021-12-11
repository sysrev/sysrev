(ns datapub.test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia.constants :as constants]
            [com.walmartlabs.lacinia.parser :as parser]
            [com.walmartlabs.lacinia.selection :as selection]
            [datapub.main :as main]
            [io.pedestal.test :as test]
            [medley.core :as medley]
            [sysrev.datapub-client.interface.queries :as dpcq])
  (:import java.util.Base64
           org.apache.commons.io.IOUtils))

(defn run-tests [opts]
  ;; https://clojureverse.org/t/why-doesnt-my-program-exit/3754/8
  ;; This prevents clojure -X:test from hanging
  ((requiring-resolve 'cognitect.test-runner.api/test) opts)
  (shutdown-agents))

(defn response-for [system verb url & options]
  (apply
   test/response-for (get-in system [:pedestal :service :io.pedestal.http/service-fn])
   verb url options))

(defn throw-errors [graphql-response]
  (if (-> graphql-response :body :errors (or (:errors graphql-response)))
    (throw (ex-info "GraphQL error response"
                    {:response graphql-response}))
    graphql-response))

(defn execute! [system query & [variables]]
  (let [sysrev-dev-key (-> system :pedestal :config :secrets :sysrev-dev-key)]
    (-> (response-for system
                      :post "/api"
                      :headers {"Authorization" (str "Bearer " sysrev-dev-key)
                                "Content-Type" "application/json"}
                      :body (json/generate-string {:query query
                                                   :variables variables}))
        (update :body json/parse-string true))))

(defn query-arguments [query]
  (-> query :selections first selection/arguments))

(defn execute-subscription! [system f query & [variables opts]]
  (let [sysrev-dev-key (-> system :pedestal :config :secrets :sysrev-dev-key)
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
                     (update :pedestal dissoc :port)
                     (update :postgres assoc :embedded? true :host "localhost"
                             :port 0 :user "postgres" :password nil)
                     (medley/deep-merge (-> (:config options#)
                                            (update :env #(or % :test))))
                     ((:get-system-map options# main/system-map))
                     component/start)
         ~name-sym system#]
     (try
       ~@body
       (finally
         (component/stop system#)))))

(defn create-dataset! [system input]
  (-> system
      (execute!
       (dpcq/m-create-dataset "id")
       {:input input})
      :body
      throw-errors
      (get-in [:data :createDataset :id])))

(def ctgov-indices
  [[:TEXT ["ProtocolSection" "ArmsInterventionsModule" "InterventionList" "Intervention" :* "InterventionName"]]
   [:TEXT ["ProtocolSection" "ConditionsModule" "ConditionList" "Condition" :*]]
   [:TEXT ["ProtocolSection" "DescriptionModule" "BriefSummary"]]
   [:TEXT ["ProtocolSection" "DescriptionModule" "DetailedDescription"]]
   [:TEXT ["ProtocolSection" "IdentificationModule" "BriefTitle"]]
   [:TEXT ["ProtocolSection" "IdentificationModule" "NCTId"]]
   [:TEXT ["ProtocolSection" "IdentificationModule" "OfficialTitle"]]])

(defn load-ctgov-dataset! [system & [dataset-id]]
  (let [ds-id (or dataset-id
                  (create-dataset!
                   system
                   {:description "ClinicalTrials.gov is a database of privately and publicly funded clinical studies conducted around the world."
                    :name "ClinicalTrials.gov"
                    :public true}))]
    (doseq [{:keys [content externalId]} (-> "datapub/ctgov-entities.edn"
                                             io/resource
                                             slurp
                                             edn/read-string)]
      (throw-errors
       (execute!
        system
        (dpcq/m-create-dataset-entity "id")
        {:input
         {:content (json/generate-string content)
          :datasetId ds-id
          :externalId externalId
          :mediaType "application/json"}})))
    (doseq [[type path] ctgov-indices]
      (throw-errors
       (execute!
        system
        (dpcq/m-create-dataset-index "type")
        {:input
         {:datasetId ds-id
          :path (pr-str path)
          :type (name type)}})))
    ds-id))

(def fda-drugs-docs-indices
  [[:TEXT ["metadata" "ApplicationDocsDescription"]]
   [:TEXT ["metadata" "ApplType"]]
   [:TEXT ["metadata" "Products" :* "ActiveIngredient"]]
   [:TEXT ["metadata" "Products" :* "DrugName"]]
   [:TEXT ["metadata" "ReviewDocumentType"]]
   [:TEXT ["text"]]])

(defn load-fda-drugs-docs-dataset! [system & [dataset-id]]
  (let [ds-id (or dataset-id
                  (create-dataset!
                   system
                   {:name "Drugs@FDA Application Documents"
                    :public true}))]
    (doseq [{:keys [external-created external-id filename grouping-id metadata]}
            #__ (-> "datapub/fda-drugs-docs-entities.edn"
                    io/resource
                    slurp
                    edn/read-string)]
      (throw-errors
       (execute!
        system
        (dpcq/m-create-dataset-entity "id")
        {:input
         {:content (->> (str "datapub/file-uploads/" filename)
                        io/resource
                        .openStream
                        IOUtils/toByteArray
                        (.encodeToString (Base64/getEncoder)))
          :datasetId ds-id
          :externalCreated external-created
          :externalId external-id
          :groupingId grouping-id
          :mediaType "application/pdf"
          :metadata (json/generate-string metadata)}})))
    (doseq [[type path] fda-drugs-docs-indices]
      (throw-errors
       (execute!
        system
        (dpcq/m-create-dataset-index "type")
        {:input
         {:datasetId ds-id
          :path (pr-str path)
          :type (name type)}})))
    ds-id))

(defn load-all-fixtures! [system]
  (load-ctgov-dataset! system)
  (when-not (get-in system [:s3 :client-opts :disabled?])
    (create-dataset! system {:name "Unused"})
    (load-fda-drugs-docs-dataset! system))
  nil)
