(ns datapub.dataset-test
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [datapub.dataset :refer :all]
            [datapub.file :as file]
            [datapub.test :as test]
            [sysrev.datapub-client.interface.queries :as dpcq])
  (:import java.io.InputStream
           java.net.URL
           java.util.Base64
           org.apache.commons.io.IOUtils))

(deftest ^{:doc "Make sure we don't inadvertently include log4j2 due to a transitive
   or vendored dependency. See CVE-2021-44228."}
  test-no-log4j2
  (test/with-test-system [system {}]
    (is (thrown? ClassNotFoundException
                 (import 'org.apache.logging.log4j.core.lookup.JndiLookup)))))

(defn parse-json
  "Convert a String or PGObject to keywordized json.

  The type returned by queries is affected by global state, so this should handle
  both argument types properly to remain robust."
  [x]
  (json/parse-string
   (if (string? x) x (.getValue x))
   true))

(defn ex! [system query & [variables]]
  (-> (test/execute! system query variables)
      :body
      test/throw-errors))

(defn subscribe-dataset-entities! [system return-keys variables & [opts]]
  {:pre [(coll? return-keys)]}
  (->> (test/execute-subscription!
        system
        dataset-entities-subscription
        (dpcq/s-dataset-entities return-keys)
        variables
        (merge {:timeout-ms 1000} opts))
       (map #(select-keys % return-keys))))

(defn subscribe-search-dataset! [system return-keys variables & [opts]]
  {:pre [(coll? return-keys)]}
  (->> (test/execute-subscription!
        system
        search-dataset-subscription
        (dpcq/s-search-dataset return-keys)
        variables
        (merge {:timeout-ms 1000} opts))
       (map #(select-keys % return-keys))))

(deftest test-dataset-ops
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-dataset"}})
                    (get-in [:data :createDataset :id]))]
      (is (integer? ds-id))
      (is (= {:data {:dataset {:name "test-dataset" :public false}}}
             (ex (dpcq/q-dataset [:name :public]) {:id ds-id})))
      (is (= {:data {:listDatasets
                     {:edges [{:node {:name "test-dataset"}}]
                      :totalCount 1}}}
             (ex (dpcq/q-list-datasets "totalCount edges{node{name}}"))))
      (testing "updateDataset works"
        (is (= {:id ds-id :public true}
               (-> (ex (dpcq/m-update-dataset [:id :public])
                       {:input {:id ds-id :public true}})
                   (get-in [:data :updateDataset]))))))))

(deftest test-list-datasets
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-ids (->> #(-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-dataset"}})
                           (get-in [:data :createDataset :id]))
                      (repeatedly 10)
                      doall)]
      (testing "totalCount is correct"
        (is (= {:data {:listDatasets {:totalCount 10}}}
               (ex (dpcq/q-list-datasets "totalCount")))))
      (testing "first arg works"
        (is (= 2
               (-> (ex (dpcq/q-list-datasets "edges{cursor}") {:first 2})
                   (get-in [:data :listDatasets :edges])
                   count))))
      (testing "Pagination with endCursor and after works"
        (let [end-cursor (-> (ex (dpcq/q-list-datasets "pageInfo{endCursor}") {:first 4})
                             (get-in [:data :listDatasets :pageInfo :endCursor]))
              end-cursor2 (-> (ex (dpcq/q-list-datasets "pageInfo{endCursor}")
                                  {:after end-cursor :first 2})
                              (get-in [:data :listDatasets :pageInfo :endCursor]))
              q (dpcq/q-list-datasets "edges{cursor}")]
          (is (string? end-cursor))
          (is (= 6
                 (-> (ex q {:after end-cursor})
                     (get-in [:data :listDatasets :edges])
                     count)))
          (is (string? end-cursor2))
          (is (= 4
                 (-> (ex q {:after end-cursor2})
                     (get-in [:data :listDatasets :edges])
                     count)))
          (testing "Different pages have no edges in common"
            (is (= 10
                   (->> [(ex q {:first 4})
                         (ex q {:after end-cursor :first 2})
                         (ex q {:after end-cursor2})]
                        (mapcat #(get-in % [:data :listDatasets :edges]))
                        (into #{})
                        count)))))))))

(deftest test-dataset-index-ops
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-dataset-index"}})
                    (get-in [:data :createDataset :id]))
          text-index (fn [path]
                       {:datasetId ds-id
                        :path (pr-str path)
                        :type :TEXT})
          create-index! (fn [input]
                          (-> (ex (dpcq/m-create-dataset-index "path type") {:input input})
                              (get-in [:data :createDatasetIndex])))]
      (testing "Can create DatasetIndex objects with string paths"
        (is (= {:path "[\"data\" \"title\"]", :type "TEXT"}
               (create-index! (text-index ["data" "title"])))))
      (testing "Can create DatasetIndex objects with wildcard paths"
        (is (= {:path "[\"data\" :* \"title\"]", :type "TEXT"}
               (create-index! (text-index ["data" :* "title"])))))
      (testing "Can create DatasetIndex objects with integer paths"
        (is (= {:path "[\"2\"]", :type "TEXT"}
               (create-index! (text-index [2]))))))))

(deftest test-entity-ops
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-entity"}})
                    (get-in [:data :createDataset :id]))
          entity-id (-> (ex (dpcq/m-create-dataset-entity "id")
                            {:input
                             {:datasetId ds-id
                              :content (json/generate-string {:a 1})
                              :mediaType "application/json"}})
                        (get-in [:data :createDatasetEntity :id]))]
      (is (pos-int? entity-id))
      (is (= {:data {:datasetEntity {:content "{\"a\": 1}" :mediaType "application/json"}}}
             (ex (dpcq/q-dataset-entity "content mediaType") {:id entity-id})))
      (testing "createDatasetEntity returns all fields"
        (is (= {:data {:createDatasetEntity {:content "{\"b\": 2}" :externalId "ex-id" :groupingId "gr-id" :mediaType "application/json"}}}
               (-> (ex (dpcq/m-create-dataset-entity "content externalId groupingId mediaType")
                       {:input
                        {:datasetId ds-id
                         :content "{\"b\": 2}"
                         :externalId "ex-id"
                         :groupingId "gr-id"
                         :mediaType "application/json"}})))))
      (testing "Can create entities after creating an index"
        (test/throw-errors
         (ex (dpcq/m-create-dataset-index "type")
             {:input {:datasetId ds-id :path (pr-str ["a"]) :type "TEXT"}}))
        (-> (ex (dpcq/m-create-dataset-entity "id")
                {:input
                 {:datasetId ds-id
                  :content (json/generate-string {:a 3})
                  :mediaType "application/json"}})
            (get-in [:data :createDatasetEntity :id])
            pos-int?
            is))
      (testing "Can list entities"
        (is (= {:data
                {:dataset
                 {:entities
                  {:edges [{:node {:id 1}} {:node {:id 2}} {:node {:id 3}}]
                   :totalCount 3}}}}
               (ex (dpcq/q-dataset "entities {totalCount edges{node{id}}}")
                   {:id ds-id}))))
      (testing "Can list entities by externalId"
        (is (= {:data
                {:dataset
                 {:entities
                  {:edges [{:node {:id 2}}]
                   :totalCount 1}}}}
               (ex "query($externalId: String, $id: PositiveInt){dataset(id:$id){entities(externalId: $externalId) {totalCount edges{node{id}}}}}"
                   {:externalId "ex-id" :id ds-id}))))
      (testing "Can list entities by groupingId"
        (is (= {:data
                {:dataset
                 {:entities
                  {:edges [{:node {:id 2}}]
                   :totalCount 1}}}}
               (ex "query($groupingId: String, $id: PositiveInt){dataset(id:$id){entities(groupingId: $groupingId) {totalCount edges{node{id}}}}}"
                   {:groupingId "gr-id" :id ds-id})))))))

(deftest test-entity-ops-with-external-ids
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          sub-json-dataset-entities! (fn [return-keys variables]
                                       (->> (subscribe-dataset-entities!
                                             system return-keys variables)
                                            (map #(update % :content parse-json))
                                            (into #{})))
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-entity"}})
                    (get-in [:data :createDataset :id]))]
      (doseq [[id v] [["A1" 1] ["A1" 1] ["A2" 1] ["A3" 1] ["B1" 1] ["B2" 1]
                      ["A1" 2] ["B1" 2] ["B2" 1] ["B1" 1] ["A1" 3]]]
        (ex (dpcq/m-create-dataset-entity "id")
            {:input
             {:datasetId ds-id
              :content (json/generate-string [id v])
              :externalId id
              :mediaType "application/json"}}))
      (testing "New entities aren't created if one exists with the same externalId"
        (let [A11-id (-> (dpcq/q-dataset
                          "entities(externalId:\"A1\"){edges{node{id}}}")
                         (ex {:id ds-id})
                         :data :dataset :entities :edges
                         (->> (map #(get-in % [:node :id]))) first)]
          (is (pos-int? A11-id))
          (is (= A11-id (-> (ex (dpcq/m-create-dataset-entity "id")
                                {:input
                                 {:content (json/generate-string ["A1" 1])
                                  :datasetId ds-id
                                  :externalId "A1"
                                  :mediaType "application/json"}})
                            :data :createDatasetEntity :id))))
        (is (= {{:content ["A1" 1] :externalId "A1"} 1
                {:content ["A1" 2] :externalId "A1"} 1
                {:content ["A1" 3] :externalId "A1"} 1
                {:content ["A2" 1] :externalId "A2"} 1
                {:content ["A3" 1] :externalId "A3"} 1
                {:content ["B1" 1] :externalId "B1"} 1
                {:content ["B1" 2] :externalId "B1"} 1
                {:content ["B2" 1] :externalId "B2"} 1}
               (frequencies
                (sub-json-dataset-entities!
                 #{:content :externalId} {:input {:datasetId ds-id}})))))
      (testing "uniqueExternalIds: true returns latest versions only"
        (is (= {{:content ["A1" 3] :externalId "A1"} 1
                {:content ["A2" 1] :externalId "A2"} 1
                {:content ["A3" 1] :externalId "A3"} 1
                {:content ["B1" 2] :externalId "B1"} 1
                {:content ["B2" 1] :externalId "B2"} 1}
               (frequencies
                (sub-json-dataset-entities!
                 #{:content :externalId}
                 {:input {:datasetId ds-id :uniqueExternalIds true}})))))
      (testing "The entity with the latest externalCreated is returned"
        (let [ds2-id (-> (ex (dpcq/m-create-dataset "id")
                             {:input {:name "test-entity2"}})
                         (get-in [:data :createDataset :id]))]
          (doseq [[id v ex-cr] [["C1" 1 "2011-12-03T10:15:30Z"]
                                ["C1" 2 "2021-12-03T10:15:30Z"]
                                ["C1" 3 "2011-12-01T10:15:30Z"]]]
            (ex (dpcq/m-create-dataset-entity "id")
                {:input
                 {:datasetId ds2-id
                  :content (json/generate-string [id v])
                  :externalCreated ex-cr
                  :externalId id
                  :mediaType "application/json"}}))
          (is (= {{:content ["C1" 2] :externalId "C1"} 1}
                 (frequencies
                  (sub-json-dataset-entities!
                   #{:content :externalId}
                   {:input {:datasetId ds2-id :uniqueExternalIds true}})))))))))

(deftest test-dataset#entities-pagination
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id")
                        {:input {:name "test-dataset-entities-pagination"}})
                    (get-in [:data :createDataset :id]))]
      (doseq [i (range 15)]
        (ex (dpcq/m-create-dataset-entity "id")
            {:input
             {:datasetId ds-id
              :content (json/generate-string {:num i})
              :mediaType "application/json"}}))
      (testing "totalCount is correct"
        (is (= {:data {:dataset {:entities {:totalCount 15}}}}
               (ex (dpcq/q-dataset#entities "totalCount") {:id ds-id}))))
      (testing "first arg works"
        (is (= 11
               (-> (ex (dpcq/q-dataset#entities "edges{cursor}") {:first 11 :id ds-id})
                   (get-in [:data :dataset :entities :edges])
                   count))))
      (testing "Pagination with endCursor and after works"
        (let [end-cursor (-> (ex (dpcq/q-dataset#entities "pageInfo{endCursor}") {:first 4 :id ds-id})
                             (get-in [:data :dataset :entities :pageInfo :endCursor]))
              end-cursor2 (-> (ex (dpcq/q-dataset#entities "pageInfo{endCursor}")
                                  {:after end-cursor :first 3 :id ds-id})
                              (get-in [:data :dataset :entities :pageInfo :endCursor]))
              q (dpcq/q-dataset#entities "edges{cursor}")]
          (is (string? end-cursor))
          (is (= 11
                 (-> (ex q {:after end-cursor :id ds-id})
                     (get-in [:data :dataset :entities :edges])
                     count)))
          (is (string? end-cursor2))
          (is (= 8
                 (-> (ex q {:after end-cursor2 :id ds-id})
                     (get-in [:data :dataset :entities :edges])
                     count)))
          (testing "Different pages have no edges in common"
            (is (= 15
                   (->> [(ex q {:first 4 :id ds-id})
                         (ex q {:after end-cursor :first 3 :id ds-id})
                         (ex q {:after end-cursor2 :id ds-id})]
                        (mapcat #(get-in % [:data :dataset :entities :edges]))
                        (into #{})
                        count)))))))))

(deftest test-datasetEntitiesById
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id")
                        {:input {:name "test-datasetEntitiesById"}})
                    (get-in [:data :createDataset :id]))
          entity-ids (mapv
                      (fn [i]
                        (-> (ex (dpcq/m-create-dataset-entity "id")
                                {:input
                                 {:datasetId ds-id
                                  :content (json/generate-string {:num i})
                                  :mediaType "application/json"}})
                            (get-in [:data :createDatasetEntity :id])))
                      (range 15))]
      (testing "totalCount is correct"
        (is (= {:data {:datasetEntitiesById {:totalCount 15}}}
               (ex (dpcq/q-dataset-entities-by-id "totalCount") {:ids entity-ids}))))
      (testing "first arg works"
        (is (= 11
               (-> (ex (dpcq/q-dataset-entities-by-id "edges{cursor}") {:first 11 :ids entity-ids})
                   (get-in [:data :datasetEntitiesById :edges])
                   count)))
        (is (= [{"num" 0} {"num" 1} {"num" 2} {"num" 3}]
               (-> (ex (dpcq/q-dataset-entities-by-id "edges{node{content}}") {:first 4 :ids entity-ids})
                   (get-in [:data :datasetEntitiesById :edges])
                   (->> (map (comp json/parse-string :content :node)))))
            "content is correct"))
      (testing "Pagination with endCursor and after works"
        (let [end-cursor (-> (ex (dpcq/q-dataset-entities-by-id "pageInfo{endCursor}") {:first 4 :ids entity-ids})
                             (get-in [:data :datasetEntitiesById :pageInfo :endCursor]))
              end-cursor2 (-> (ex (dpcq/q-dataset-entities-by-id "pageInfo{endCursor}")
                                  {:after end-cursor :first 3 :ids entity-ids})
                              (get-in [:data :datasetEntitiesById :pageInfo :endCursor]))
              q (dpcq/q-dataset-entities-by-id "edges{cursor}")]
          (is (string? end-cursor))
          (is (= 11
                 (-> (ex q {:after end-cursor :ids entity-ids})
                     (get-in [:data :datasetEntitiesById :edges])
                     count)))
          (is (string? end-cursor2))
          (is (= 8
                 (-> (ex q {:after end-cursor2 :ids entity-ids})
                     (get-in [:data :datasetEntitiesById :edges])
                     count)))
          (is (= [{"num" 7} {"num" 8} {"num" 9} {"num" 10}]
                 (-> (ex (dpcq/q-dataset-entities-by-id "edges{node{content}}") {:after end-cursor2 :first 4 :ids entity-ids})
                     (get-in [:data :datasetEntitiesById :edges])
                     (->> (map (comp json/parse-string :content :node)))))
              "content is correct")
          (testing "Different pages have no edges in common"
            (is (= 15
                   (->> [(ex q {:first 4 :ids entity-ids})
                         (ex q {:after end-cursor :first 3 :ids entity-ids})
                         (ex q {:after end-cursor2 :ids entity-ids})]
                        (mapcat #(get-in % [:data :datasetEntitiesById :edges]))
                        (into #{})
                        count)))))))))

(deftest test-dataset-entities-subscription
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          sub-json-dataset-entities! (fn [return-keys variables]
                                       (->> (subscribe-dataset-entities!
                                             system return-keys variables)
                                            (map #(update % :content parse-json))
                                            (into #{})))
          ds-id (-> (ex (dpcq/m-create-dataset "id")
                        {:input {:name "test-dataset-entities"}})
                    (get-in [:data :createDataset :id]))]
      (doseq [i (range 3)]
        (ex (dpcq/m-create-dataset-entity "id")
            {:input
             {:datasetId ds-id
              :content (json/generate-string {:num i})
              :mediaType "application/json"}}))
      (is (= #{{:content {:num 0} :mediaType "application/json"}
               {:content {:num 1} :mediaType "application/json"}
               {:content {:num 2} :mediaType "application/json"}}
             (sub-json-dataset-entities!
              #{:content :mediaType}
              {:input {:datasetId ds-id}}))))))

(deftest test-search-dataset-subscription
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          sub-search-dataset! (fn [return-keys variables]
                                (->> variables
                                     (subscribe-search-dataset!
                                      system return-keys)
                                     (into #{})))
          ds-id (test/load-ctgov-dataset! system)
          brief-summary {:datasetId ds-id
                         :path (pr-str ["ProtocolSection" "DescriptionModule" "BriefSummary"])
                         :type :TEXT}
          condition {:datasetId ds-id
                     :path (pr-str ["ProtocolSection" "ConditionsModule" "ConditionList" "Condition" :*])
                     :type :TEXT}
          overall-status {:datasetId ds-id
                          :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])
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
      (ex (dpcq/m-create-dataset-index "type") {:input brief-summary})
      (ex (dpcq/m-create-dataset-index "type") {:input overall-status})
      (is (= #{{:externalId "NCT04982952"} {:externalId "NCT04983004"}}
             (sub-search-dataset!
              #{:externalId} (search-q brief-summary "general"))))
      (is (= #{{:externalId "NCT04982978"}}
             (sub-search-dataset!
              #{:externalId} (search-q brief-summary "youtube"))))
      (is (empty?
           (sub-search-dataset!
            #{:externalId} (search-q brief-summary "eueuoxuexauyoutube"))))
      (testing "Wildcard indices"
        (ex (dpcq/m-create-dataset-index "type") {:input primary-outcome})
        (is (= #{{:externalId "NCT04982978"}
                 {:externalId "NCT04982887"}
                 {:externalId "NCT04983004"}}
               (sub-search-dataset!
                #{:externalId} (search-q primary-outcome "scale")))))
      (testing "Phrase search"
        (ex (dpcq/m-create-dataset-index "type") {:input primary-outcome})
        (is (= #{{:externalId "NCT04982991"}}
               (sub-search-dataset!
                #{:externalId} (search-q brief-summary "\"single oral doses\"")))))
      (testing "OR search"
        (is (= #{{:externalId "NCT04982926"} {:externalId "NCT04982939"}}
               (sub-search-dataset!
                #{:externalId}
                {:input
                 {:datasetId ds-id
                  :query
                  {:type :OR
                   :text
                   [{:paths [(:path condition)]
                     :search "\"breast cancer\""}
                    {:paths [(:path brief-summary)]
                     :search "sintilimab"}]}}}))))
      (testing "Complex search with nested ANDs and ORs"
        (is (= #{{:externalId "NCT04982900"} {:externalId "NCT04983004"}}
               (sub-search-dataset!
                #{:externalId}
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
                       :search "tele-rehabilitation"}]}]}}}))))
      (testing "String equality search (case-sensitive)"
        (is (= #{{:externalId "NCT04982978"}}
               (sub-search-dataset!
                #{:externalId}
                {:input
                 {:datasetId ds-id
                  :query
                  {:type :AND
                   :string
                   [{:eq "Completed"
                     :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])}]}}})))
        (is (empty?
             (sub-search-dataset!
              #{:externalId}
              {:input
               {:datasetId ds-id
                :query
                {:type :AND
                 :string
                 [{:eq "not yet recruiting"
                   :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])}]}}}))))
      (testing "String equality search (case-insensitive)"
        (is (= #{{:externalId "NCT04983004"} {:externalId "NCT04982991"}
                 {:externalId "NCT04982965"} {:externalId "NCT04982952"}
                 {:externalId "NCT04982926"} {:externalId "NCT04982913"}
                 {:externalId "NCT04982900"}}
               (sub-search-dataset!
                #{:externalId}
                {:input
                 {:datasetId ds-id
                  :query
                  {:type :AND
                   :string
                   [{:eq "not yet recruiting"
                     :ignoreCase true
                     :path (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"])}]}}}))))
      (testing "String equality search with wildcard in path"
        (is (= #{{:externalId "NCT04982978"}}
               (sub-search-dataset!
                #{:externalId}
                {:input
                 {:datasetId ds-id
                  :query
                  {:type :AND
                   :string
                   [{:eq "Vaping Related Disorder"
                     :path (pr-str ["ProtocolSection" "ConditionsModule" "ConditionList" "Condition" :*])}]}}}))))
      (testing "uniqueExternalIds works and uses externalCreated to choose"
        (let [ds2-id (-> (ex (dpcq/m-create-dataset "id")
                             {:input {:name "uniqueExternalIds-externalCreated"}})
                         (get-in [:data :createDataset :id]))
              first-idx {:datasetId ds2-id
                         :path (pr-str ["0"])
                         :type :TEXT}]
          (doseq [[id v ex-cr] [["cat" 1 "2011-12-03T10:15:30Z"]
                                ["cat" 2 "2021-12-03T10:15:30Z"]
                                ["cat" 3 "2011-12-01T10:15:30Z"]]]
            (ex (dpcq/m-create-dataset-entity "id")
                {:input
                 {:datasetId ds2-id
                  :content (json/generate-string [id v])
                  :externalCreated ex-cr
                  :externalId id
                  :mediaType "application/json"}}))
          (ex (dpcq/m-create-dataset-index "type") {:input first-idx})
          (is (= [{:content ["cat" 2]}]
                 (->> (sub-search-dataset!
                       #{:content}
                       {:input
                        {:datasetId ds2-id
                         :uniqueExternalIds true
                         :query
                         {:type :AND
                          :string
                          [{:eq "cat"
                            :path (pr-str ["0"])}]}}})
                      (map (fn [{:keys [content]}]
                             {:content (some-> content json/parse-string)}))))))))))

(deftest test-unique-grouping-ids
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          sub-json-dataset-entities! (fn [return-keys variables]
                                       (->> (subscribe-dataset-entities!
                                             system return-keys variables)
                                            (map #(update % :content parse-json))
                                            (into #{})))
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-grouping"}})
                    (get-in [:data :createDataset :id]))
          sub-json-dataset-entities! (fn [return-keys variables]
                                       (->> (subscribe-dataset-entities!
                                             system return-keys variables)
                                            (map #(update % :content parse-json))
                                            (into #{})))
          sub-search-dataset! (fn [return-keys variables]
                                (->> variables
                                     (subscribe-search-dataset!
                                      system return-keys)
                                     (into #{})))
          search-q (fn [idx search]
                     {:input
                      {:datasetId ds-id
                       :uniqueGroupingIds true
                       :query
                       {:type :AND
                        :text
                        [{:paths [(:path idx)]
                          :search search}]}}})
          second-index {:datasetId ds-id
                        :path (pr-str [2])
                        :type :TEXT}]
      (doseq [[ex-id gr-id v ex-created]
              [["A1" "g1" "term 1" "2000-01-01T00:00:00Z"]
               ["A2" "g1" "term 2" "2000-01-02T00:00:00Z"]
               ["A1" "g1" "term 3" "2000-01-03T00:00:00Z"]
               ["B1" "g2" "term 1" "2000-01-01T00:00:00Z"]
               ["B1" "g2" "term 3" "2000-01-03T00:00:00Z"]
               ["B2" "g2" "term 2" "2000-01-02T00:00:00Z"]
               ["C1" "g3" "term 3" "2000-01-03T00:00:00Z"]
               ["C2" "g3" "term 2" "2000-01-02T00:00:00Z"]
               ["C1" "g3" "term 1" "2000-01-01T00:00:00Z"]
               ;; Entities with the same externalCreated should
               ;; break ties with the actual created Timestamp
               ["D1" "g4" "term 1" "2000-01-01T00:00:00Z"]
               ["D2" "g4" "term 2" "2000-01-01T00:00:00Z"]
               ["E2" "g5" "term 2" "2000-01-01T00:00:00Z"]
               ["E1" "g5" "term 1" "2000-01-01T00:00:00Z"]]]
        (ex (dpcq/m-create-dataset-entity "id")
            {:input
             {:datasetId ds-id
              :content (json/generate-string [ex-id gr-id v])
              :externalCreated ex-created
              :externalId ex-id
              :groupingId gr-id
              :mediaType "application/json"}}))
      (testing "datasetEntities with uniqueGroupingIds returns the most recent entity of each group"
        (ex (dpcq/m-create-dataset-index "type") {:input second-index})
        (is (= #{["A1" "g1" "term 3"] ["B1" "g2" "term 3"] ["C1" "g3" "term 3"]
                 ["D2" "g4" "term 2"] ["E1" "g5" "term 1"]}
               (->> (sub-json-dataset-entities!
                     #{:content} {:input {:datasetId ds-id :uniqueGroupingIds true}})
                    (map :content) (into #{})))))
      (testing "searchDataset with uniqueGroupingIds returns the most recent entity of each group"
        (ex (dpcq/m-create-dataset-index "type") {:input second-index})
        (is (= #{["A1" "g1" "term 3"] ["B1" "g2" "term 3"] ["C1" "g3" "term 3"]
                 ["D2" "g4" "term 2"] ["E1" "g5" "term 1"]}
               (->> (sub-search-dataset!
                     #{:content} (search-q second-index "term"))
                    (map (comp json/parse-string :content))
                    (into #{}))))))))

(deftest test-pdf-entities
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id")
                        {:input {:name "test-pdf-entities"}})
                    (get-in [:data :createDataset :id]))
          create-entity-raw
          #__ (fn [filename & [metadata]]
                (let [content (->> (str "datapub/file-uploads/" filename)
                                   io/resource
                                   .openStream
                                   IOUtils/toByteArray
                                   (.encodeToString (Base64/getEncoder)))]
                  {:content content
                   :response
                   (:body
                    (test/execute!
                     system
                     (dpcq/m-create-dataset-entity #{:id :metadata})
                     {:input
                      {:datasetId ds-id
                       :content content
                       :externalId filename
                       :mediaType "application/pdf"
                       :metadata (when metadata
                                   (json/generate-string metadata))}}))}))
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
               (ex (dpcq/q-dataset-entity "content mediaType")
                   {:id (:id armstrong)}))))
      (testing "Identical files with different metadata are separate entities, but identical files with identical metadata are the same entity"
        (let [armstrong2 (create-entity "armstrong-thesis-2003-abstract.pdf"
                                        {:title "Armstrong Thesis Abstract2"})
              armstrong* (-> (ex (dpcq/q-dataset-entity "id metadata")
                                 {:id (:id armstrong)})
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
        (is (re-matches
             #"Invalid content.*: Not a valid PDF file."
             (-> (create-entity-raw "armstrong-thesis-2003-abstract.docx")
                 (get-in [:response :errors 0 :message])))))
      (testing "Invalid metadata is rejected"
        (is (= "Invalid metadata: Not valid JSON."
               (-> (test/execute!
                    system
                    (dpcq/m-create-dataset-entity "id")
                    {:input
                     {:datasetId ds-id
                      :content (:content armstrong)
                      :externalId "armstrong-thesis-2003-abstract.pdf"
                      :mediaType "application/pdf"
                      :metadata "{"}})
                   (get-in [:body :errors 0 :message])))))
      (let [ocr-text {:datasetId ds-id
                      :path (pr-str ["ocr-text"])
                      :type :TEXT}
            text {:datasetId ds-id
                  :path (pr-str ["text"])
                  :type :TEXT}
            title {:datasetId ds-id
                   :path (pr-str ["metadata" "title"])
                   :type :TEXT}
            search-q (fn [idx search]
                       {:input
                        {:datasetId ds-id
                         :query
                         {:type :AND
                          :text
                          [{:paths [(:path idx)]
                            :search search}]}}})
            sub-search-dataset! (fn [return-keys variables]
                                  (->> variables
                                       (subscribe-search-dataset!
                                        system return-keys)
                                       (into #{})))
            ex-search! (partial sub-search-dataset! #{:externalId})]
        (doseq [idx [ocr-text text title]]
          (ex (dpcq/m-create-dataset-index "type") {:input idx}))
        (testing "Can search PDFs based on metadata"
          (is (= #{{:externalId "armstrong-thesis-2003-abstract.pdf"}}
                 (ex-search! (search-q title "\"Armstrong Thesis Abstract\"")))))
        (testing "Can search PDFs based on their text content"
          (is (= #{{:externalId "armstrong-thesis-2003-abstract.pdf"}
                   {:externalId "ctgov-Prot_SAP_000.pdf"}}
                 (ex-search! (search-q text "systems"))))
          (is (= #{{:externalId "armstrong-thesis-2003-abstract.pdf"}}
                 (ex-search! (search-q text "\"reliable distributed systems\""))))
          (is (= #{{:externalId "fda-008372Orig1s044ltr.pdf"}}
                 (ex-search! (search-q text "sNDA"))))
          (is (empty? (ex-search! (search-q text "eueuoxuexau")))))
        (testing "Scanned PDFs are processed with OCR and are searchable"
          (let [ziagen (create-entity "020978_S016_ZIAGEN.pdf")]
            (is (pos-int? (:id ziagen)))
            (is (= #{{:externalId "020978_S016_ZIAGEN.pdf"}}
                   (ex-search! (search-q ocr-text "abacavir"))))
            (is (= #{{:externalId "020978_S016_ZIAGEN.pdf"}}
                   (ex-search! (search-q ocr-text "\"tanima sinha\""))))))))))

(deftest test-file-uploads
  (test/with-test-system [system {:config {:pedestal {:port 0}}}]
    (let [api-url (str "http://localhost:" (get-in system [:pedestal :bound-port])
                       "/api")
          sysrev-dev-key (get-in system [:pedestal :config :secrets :sysrev-dev-key])
          ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-file-uploads"
                                                              :public true}})
                    (get-in [:data :createDataset :id]))
          upload-entity! (fn [content mediaType]
                           (-> {:headers {"Authorization" (str "Bearer " sysrev-dev-key)}
                                :multipart
                                [{:name "operations"
                                  :content
                                  (json/generate-string
                                   {:query (dpcq/m-create-dataset-entity "content contentUrl id")
                                    :variables
                                    {:input
                                     {:datasetId ds-id
                                      :mediaType mediaType}}})}
                                 {:name "map"
                                  :content
                                  (json/generate-string
                                   {"0" ["variables.input.contentUpload"]})}
                                 {:name "0"
                                  :content content}]}
                               (->> (client/post api-url))
                               :body
                               (json/parse-string keyword)
                               test/throw-errors
                               (get-in [:data :createDatasetEntity])))
          upload-stream (fn [fname]
                          (->> (str "datapub/file-uploads/" fname)
                               io/resource
                               .openStream))
          sha3-256 (fn [^InputStream in]
                     (-> in file/sha3-256
                         (->> (.encode (Base64/getEncoder)))
                         String.))]
      (testing "JSON uploads through contentUpload work and can be retrieved at contentUrl"
        (let [entity-a (upload-entity! (json/generate-string {"a" [0]}) "application/json")]
          (is (pos-int? (:id entity-a)))
          (is (string? (:contentUrl entity-a)))
          (is (= {"a" [0]}
                 (some-> entity-a :content json/parse-string)
                 (some-> entity-a :contentUrl URL. .openStream slurp json/parse-string)))))
      (testing "PDF uploads through contentUpload work and can be retrieved at contentUrl"
        (let [armstrong (upload-entity! (upload-stream "armstrong-thesis-2003-abstract.pdf")
                                        "application/pdf")]
          (is (pos-int? (:id armstrong)))
          (is (string? (:contentUrl armstrong)))
          (is (= (sha3-256 (upload-stream "armstrong-thesis-2003-abstract.pdf"))
                 (some-> armstrong :contentUrl URL. .openStream sha3-256))))))))
