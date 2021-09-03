(ns datapub.dataset-test
  (:require [cheshire.core :as json]
            [datapub.test :as test])
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
      (is (integer? entity-id))
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
                              select-keys #{:content :externalId :mediaType}))))))))

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
                     (into #{}))))))))
