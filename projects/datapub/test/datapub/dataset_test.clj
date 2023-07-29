(ns datapub.dataset-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [datapub.dataset :refer :all]
            [datapub.test :as test]
            [sysrev.datapub-client.interface.queries :as dpcq]))

(deftest ^{:doc "Make sure we don't inadvertently include log4j2 due to a transitive
   or vendored dependency. See CVE-2021-44228."}
  test-no-log4j2
  (test/with-test-system [_ {}]
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

(deftest test-dataset-ops
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-dataset"}})
                    (get-in [:data :createDataset :id]))]
      (is (string? ds-id))
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
    (let [ex (partial ex! system)]
      (->> #(-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-dataset"}})
                (get-in [:data :createDataset :id]))
           (repeatedly 10)
           doall)
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
               (create-index! (text-index [2])))))
      (testing "dataset#datasetIndices returns the correct indices"
        (is (= #{{:path "[\"data\" \"title\"]", :type "TEXT"}
                 {:path "[\"data\" \":datapub/*\" \"title\"]", :type "TEXT"}
                 {:path "[\"2\"]", :type "TEXT"}}
               (-> (dpcq/q-dataset "indices{path type}")
                   (ex {:id ds-id})
                   :data :dataset :indices set)))))))

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

(deftest test-search-dataset-subscription
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          sub-search-dataset! (fn [return-keys variables]
                                (->> variables
                                     (test/subscribe-search-dataset!
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

(deftest test-search-dataset-unique-grouping-ids
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test-grouping"}})
                    (get-in [:data :createDataset :id]))
          sub-search-dataset! (fn [return-keys variables]
                                (->> variables
                                     (test/subscribe-search-dataset!
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
      (testing "searchDataset with uniqueGroupingIds returns the most recent entity of each group"
        (ex (dpcq/m-create-dataset-index "type") {:input second-index})
        (is (= #{["A1" "g1" "term 3"] ["B1" "g2" "term 3"] ["C1" "g3" "term 3"]
                 ["D2" "g4" "term 2"] ["E1" "g5" "term 1"]}
               (->> (sub-search-dataset!
                     #{:content} (search-q second-index "term"))
                    (map (comp json/parse-string :content))
                    (into #{}))))))))
