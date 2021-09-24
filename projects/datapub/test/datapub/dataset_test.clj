(ns datapub.dataset-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [datapub.test :as test])
  (:import (java.util Base64)
           (org.apache.commons.io IOUtils))
  (:use clojure.test
        datapub.dataset))

(defn parse-json
  "Convert a String or PGObject to keywordized json.

  The type returned by queries is affected by global state, so this should handle
  both argument types properly to remain robust."
  [x]
  (json/parse-string
   (if (string? x) x (.getValue x))
   true))

(deftest test-dataset-ops
  (test/with-test-system [system {}]
    (let [ex (fn [query & [variables]]
               (:body (test/execute system query variables)))
          ds-id (-> (ex test/create-dataset {:input {:name "test-dataset"}})
                    test/throw-errors
                    (get-in [:data :createDataset :id]))]
      (is (integer? ds-id))
      (is (= {:data {:dataset {:name "test-dataset"}}}
             (ex "query Q($id: PositiveInt){dataset(id: $id){name}}"
                 {:id ds-id})))
      (is (= {:data {:listDatasets
                     {:edges [{:node {:name "test-dataset"}}]
                      :totalCount 1}}}
             (ex "{listDatasets{totalCount edges{node{name}}}}"))))))

(deftest test-entity-ops
  (test/with-test-system [system {}]
    (let [ex (fn [query & [variables]]
               (:body (test/execute system query variables)))
          ds-id (-> (ex test/create-dataset {:input {:name "test-entity"}})
                    test/throw-errors
                    (get-in [:data :createDataset :id]))
          entity-id (-> (ex test/create-json-dataset-entity
                            {:datasetId ds-id
                             :content (json/generate-string {:a 1})})
                        test/throw-errors
                        (get-in [:data :createDatasetEntity :id]))]
      (is (pos-int? entity-id))
      (is (= {:data {:datasetEntity {:content "{\"a\": 1}" :mediaType "application/json"}}}
             (ex "query Q($id: PositiveInt!){datasetEntity(id: $id){content mediaType}}"
                 {:id entity-id})))
      (testing "createDatasetEntity returns all fields"
        (is (= {:data {:createDatasetEntity {:content "{\"b\": 2}" :externalId "exid" :mediaType "application/json"}}}
               (-> (ex test/create-json-dataset-entity
                       {:datasetId ds-id
                        :content "{\"b\": 2}"
                        :externalId "exid"})
                   (update-in [:data :createDatasetEntity]
                              select-keys #{:content :externalId :mediaType})))))
      (testing "Can create entities after creating an index"
        (test/throw-errors
         (ex test/create-dataset-index
             {:datasetId ds-id :path (pr-str ["a"]) :type "TEXT"}))
        (-> (ex test/create-json-dataset-entity
                {:datasetId ds-id
                 :content (json/generate-string {:a 1})})
            test/throw-errors
            (get-in [:data :createDatasetEntity :id])
            pos-int?
            is)))))

(deftest test-entity-ops-with-external-ids
  (test/with-test-system [system {}]
    (let [ex (fn [query & [variables]]
               (:body (test/execute system query variables)))
          ds-id (-> (ex test/create-dataset {:input {:name "test-entity"}})
                    test/throw-errors
                    (get-in [:data :createDataset :id]))]
      (doseq [[id v] [["A1" 1] ["A1" 1] ["A2" 1] ["A3" 1] ["B1" 1] ["B2" 1]
                      ["A1" 2] ["B1" 2] ["B2" 1] ["B1" 1] ["A1" 3]]]
        (test/throw-errors
         (ex test/create-json-dataset-entity
             {:datasetId ds-id
              :content (json/generate-string [id v])
              :externalId id})))
      (is (= {{:content ["A1" 1] :externalId "A1"} 1
              {:content ["A1" 2] :externalId "A1"} 1
              {:content ["A1" 3] :externalId "A1"} 1
              {:content ["A2" 1] :externalId "A2"} 1
              {:content ["A3" 1] :externalId "A3"} 1
              {:content ["B1" 1] :externalId "B1"} 2
              {:content ["B1" 2] :externalId "B1"} 1
              {:content ["B2" 1] :externalId "B2"} 1}
             (->> (test/execute-subscription system dataset-entities-subscription test/subscribe-dataset-entities {:id ds-id} {:timeout-ms 1000})
                  (map (fn [m] (-> m
                                   (select-keys #{:content :externalId})
                                   (update :content parse-json))))
                  frequencies)))
      (testing "uniqueExternalIds: true returns latest versions only"
        (is (= {{:content ["A1" 3] :externalId "A1"} 1
                {:content ["A2" 1] :externalId "A2"} 1
                {:content ["A3" 1] :externalId "A3"} 1
                {:content ["B1" 1] :externalId "B1"} 1
                {:content ["B2" 1] :externalId "B2"} 1}
               (->> (test/execute-subscription system dataset-entities-subscription test/subscribe-dataset-entities {:id ds-id :uniqueExternalIds true} {:timeout-ms 1000})
                    (map (fn [m] (-> m
                                     (select-keys #{:content :externalId})
                                     (update :content parse-json))))
                    frequencies)))))))

(deftest test-dataset-entities-subscription
  (test/with-test-system [system {}]
    (let [ex (fn [query & [variables]]
               (:body (test/execute system query variables)))
          ds-id (-> (ex test/create-dataset {:input {:name "test-dataset-entities"}})
                    test/throw-errors
                    (get-in [:data :createDataset :id]))]
      (doseq [i (range 3)]
        (test/throw-errors
         (ex test/create-json-dataset-entity
             {:datasetId ds-id
              :content (json/generate-string {:num i})})))
      (is (= #{{:content {:num 0} :mediaType "application/json"}
               {:content {:num 1} :mediaType "application/json"}
               {:content {:num 2} :mediaType "application/json"}}
             (->> (test/execute-subscription system dataset-entities-subscription test/subscribe-dataset-entities {:id ds-id} {:timeout-ms 1000})
                  (map (fn [m] (-> m
                                   (select-keys #{:content :mediaType})
                                   (update :content parse-json))))
                  (into #{})))))))

(deftest test-search-dataset-subscription
  (test/with-test-system [system {}]
    (let [ex (fn [query & [variables]]
               (:body (test/execute system query variables)))
          ds-id (test/load-ctgov-dataset! system)
          brief-summary {:datasetId ds-id
                         :path (pr-str ["ProtocolSection" "DescriptionModule" "BriefSummary"])
                         :type :TEXT}
          condition {:datasetId ds-id
                     :path (pr-str ["ProtocolSection" "ConditionsModule" "ConditionList" "Condition" :*])
                     :type :TEXT}
          primary-outcome {:datasetId ds-id
                           :path (pr-str ["ProtocolSection" "OutcomesModule" "PrimaryOutcomeList" "PrimaryOutcome" :* "PrimaryOutcomeDescription"])
                           :type :TEXT}
          search-q (fn [idx search]
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :AND
                        :text
                        [{:paths [(:path idx)]
                          :search search}]}}})]
      (test/throw-errors
       (ex test/create-dataset-index brief-summary))
      (is (=  #{{:externalId "NCT04982952"} {:externalId "NCT04983004"}}
             (->> (test/execute-subscription
                   system search-dataset-subscription test/subscribe-search-dataset
                   (search-q brief-summary "general")
                   {:timeout-ms 1000})
                  (map (fn [m] (select-keys m #{:externalId})))
                  (into #{}))))
      (is (=  #{{:externalId "NCT04982978"}}
             (->> (test/execute-subscription
                   system search-dataset-subscription test/subscribe-search-dataset
                   (search-q brief-summary "youtube")
                   {:timeout-ms 1000})
                  (map (fn [m] (select-keys m #{:externalId})))
                  (into #{}))))
      (is (empty?
           (->> (test/execute-subscription
                 system search-dataset-subscription test/subscribe-search-dataset
                 (search-q brief-summary "eueuoxuexau")
                 {:timeout-ms 1000}))))
      (testing "Wildcard indices"
        (test/throw-errors
         (ex test/create-dataset-index primary-outcome))
        (is (=  #{{:externalId "NCT04982978"}
                  {:externalId "NCT04982887"}
                  {:externalId "NCT04983004"}}
                (->> (test/execute-subscription
                      system search-dataset-subscription test/subscribe-search-dataset
                      (search-q primary-outcome "scale")
                      {:timeout-ms 1000})
                     (map (fn [m] (select-keys m #{:externalId})))
                     (into #{})))))
      (testing "Phrase search"
        (test/throw-errors
         (ex test/create-dataset-index primary-outcome))
        (is (=  #{{:externalId "NCT04982991"}}
                (->> (test/execute-subscription
                      system search-dataset-subscription test/subscribe-search-dataset
                      (search-q brief-summary "\"single oral doses\"")
                      {:timeout-ms 1000})
                     (map (fn [m] (select-keys m #{:externalId})))
                     (into #{})))))
      (testing "OR search"
        (is (= #{{:externalId "NCT04982926"} {:externalId "NCT04982939"}}
               (->> (test/execute-subscription
                     system search-dataset-subscription test/subscribe-search-dataset
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :OR
                        :text
                        [{:paths [(:path condition)]
                          :search "\"breast cancer\""}
                         {:paths [(:path brief-summary)]
                          :search "sintilimab"}]}}}
                     {:timeout-ms 1000})
                    (map (fn [m] (select-keys m #{:externalId})))
                    (into #{})))))
      (testing "Complex search with nested ANDs and ORs"
        (is (= #{{:externalId "NCT04982900"} {:externalId "NCT04983004"}}
               (->> (test/execute-subscription
                     system search-dataset-subscription test/subscribe-search-dataset
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :OR
                        :query
                        [{:type :AND
                          :text
                          [{:paths [(:path condition)]
                            :search "cancer"}
                           {:paths [(:path brief-summary)]
                            :search "EGFR-TKI"}]}
                         {:type :AND
                          :text
                          [{:useEveryIndex true
                            :search "treatment"}
                           {:paths [(:path brief-summary)]
                            :search "tele-rehabilitation"}]}]}}}
                     {:timeout-ms 1000})
                    (map (fn [m] (select-keys m #{:externalId})))
                    (into #{})))))
      (testing "String equality search (case-sensitive)"
        (is (= #{{:externalId "NCT04982978"}}
               (->> (test/execute-subscription
                     system search-dataset-subscription test/subscribe-search-dataset
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :AND
                        :string
                        [{:eq "Completed"
                          :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])}]}}}
                     {:timeout-ms 1000})
                    (map (fn [m] (select-keys m #{:externalId})))
                    (into #{}))))
        (is (= #{}
               (->> (test/execute-subscription
                     system search-dataset-subscription test/subscribe-search-dataset
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :AND
                        :string
                        [{:eq "not yet recruiting"
                          :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])}]}}}
                     {:timeout-ms 1000})
                    (map (fn [m] (select-keys m #{:externalId})))
                    (into #{})))))
      (testing "String equality search (case-insensitive)"
        (is (= #{{:externalId "NCT04983004"} {:externalId "NCT04982991"}
                 {:externalId "NCT04982965"} {:externalId "NCT04982952"}
                 {:externalId "NCT04982926"} {:externalId "NCT04982913"}
                 {:externalId "NCT04982900"}}
               (->> (test/execute-subscription
                     system search-dataset-subscription test/subscribe-search-dataset
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :AND
                        :string
                        [{:eq "not yet recruiting"
                          :ignoreCase true
                          :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])}]}}}
                     {:timeout-ms 1000})
                    (map (fn [m] (select-keys m #{:externalId})))
                    (into #{})))))
      (testing "String equality search with wildcard in path"
        (is (= #{{:externalId "NCT04982978"}}
               (->> (test/execute-subscription
                     system search-dataset-subscription test/subscribe-search-dataset
                     {:input
                      {:datasetId ds-id
                       :query
                       {:type :AND
                        :string
                        [{:eq "Vaping Related Disorder"
                          :path (pr-str ["ProtocolSection" "ConditionsModule" "ConditionList" "Condition" :*])}]}}}
                     {:timeout-ms 1000})
                    (map (fn [m] (select-keys m #{:externalId})))
                    (into #{}))))))))

(deftest test-pdf-entities
  (test/with-test-system [system {}]
    (let [ex (fn [query & [variables]]
               (:body (test/execute system query variables)))
          ds-id (-> (ex test/create-dataset {:input {:name "test-pdf-entities"}})
                    test/throw-errors
                    (get-in [:data :createDataset :id]))
          create-entity-raw
          #__ (fn [filename & [metadata]]
                (let [content (->> (str "datapub/file-uploads/" filename)
                                   io/resource
                                   .openStream
                                   IOUtils/toByteArray
                                   (.encodeToString (Base64/getEncoder)))]
                  {:content content
                   :response (ex test/create-dataset-entity
                                 {:datasetId ds-id
                                  :content content
                                  :externalId filename
                                  :mediaType "application/pdf"
                                  :metadata (when metadata
                                              (json/generate-string metadata))})}))
          create-entity
          #__ (fn [filename & [metadata]]
                (let [{:keys [content response]} (create-entity-raw filename metadata)]
                  (-> (test/throw-errors response)
                      (get-in [:data :createDatasetEntity])
                      (assoc :content content))))
          armstrong (create-entity "armstrong-thesis-2003-abstract.pdf"
                                   {:title "Armstrong Thesis Abstract"})
          ctgov (create-entity "ctgov-Prot_SAP_000.pdf")
          fda (create-entity "fda-008372Orig1s044ltr.pdf")]
      (testing "Can create and retrieve a PDF entity"
        (is (pos-int? (:id armstrong)))
        (is (= {"title" "Armstrong Thesis Abstract"}
               (some-> armstrong :metadata json/parse-string)))
        (is (= {:data {:datasetEntity {:content (:content armstrong) :mediaType "application/pdf"}}}
               (ex "query Q($id: PositiveInt!){datasetEntity(id: $id){content mediaType}}"
                   {:id (:id armstrong)}))))
      (testing "Identical files with different metadata are separate entities, but identical files with identical metadata are the same entity"
        (let [armstrong2 (create-entity "armstrong-thesis-2003-abstract.pdf"
                                        {:title "Armstrong Thesis Abstract2"})
              armstrong* (-> (ex test/dataset-entity {:id (:id armstrong)})
                             test/throw-errors
                             (get-in [:data :datasetEntity]))]
          (is (pos-int? (:id armstrong*)))
          (is (pos-int? (:id armstrong2)))
          (is (not= (:id armstrong*) (:id armstrong2)))
          (is (= {"title" "Armstrong Thesis Abstract"}
                 (some-> armstrong* :metadata json/parse-string)))
          (is (= {"title" "Armstrong Thesis Abstract2"}
                 (some-> armstrong2 :metadata json/parse-string)))
          (is (= (:id armstrong2)
                 (:id (create-entity "armstrong-thesis-2003-abstract.pdf"
                                          {:title "Armstrong Thesis Abstract2"}))))))
      (testing "Invalid PDFs are rejected"
        (is (= "Invalid content: Not a valid PDF file."
               (-> (create-entity-raw "armstrong-thesis-2003-abstract.docx")
                   (get-in [:response :errors 0 :message])))))
      (let [text {:datasetId ds-id
                  :path (pr-str ["text"])
                  :type :TEXT}
            search-q (fn [idx search]
                       {:input
                        {:datasetId ds-id
                         :query
                         {:type :AND
                          :text
                          [{:paths [(:path idx)]
                            :search search}]}}})
            ex-search (fn [q]
                        (->> (test/execute-subscription
                              system search-dataset-subscription
                              test/subscribe-search-dataset
                              q
                              {:timeout-ms 1000})
                             (map (fn [m] (select-keys m #{:externalId})))
                             (into #{})))]
        (test/throw-errors
         (ex test/create-dataset-index text))
        (testing "Can search PDFs based on their text content"
          (is (= #{{:externalId "armstrong-thesis-2003-abstract.pdf"}
                   {:externalId "ctgov-Prot_SAP_000.pdf"}}
                 (ex-search (search-q text "systems"))))
          (is (= #{{:externalId "armstrong-thesis-2003-abstract.pdf"}}
                 (ex-search (search-q text "\"reliable distributed systems\""))))
          (is (= #{{:externalId "fda-008372Orig1s044ltr.pdf"}}
                 (ex-search (search-q text "sNDA"))))
          (is (empty? (ex-search (search-q text "eueuoxuexau")))))))))
