(ns sysrev.project-api.core-test
  (:require [clojure.test :refer :all]
            [sysrev.sysrev-api-client.interface.queries :as sacq]
            [sysrev.sysrev-api.test :as api-test]
            [sysrev.test.core :as test]))

(defn ex! [system query & [variables opts]]
  (-> (api-test/execute! system query variables opts)
      :body
      api-test/throw-errors))

(deftest ^:integration test-create-project!
  (test/with-test-system [system {}]
    (let [api-token (:api-token (test/create-test-user system))
          execute! #(:body (api-test/execute! system % %2 {:api-token api-token}))
          create-project! (fn [project]
                            (execute! (sacq/create-project "project {id name public}")
                                      {:input {:create project}}))
          response (create-project! {:name "a" :public true})
          project-1-id (get-in response [:data :createProject :project :id])]
      (testing "Users can create projects and retrieve them afterward"
        (is (= {:data {:createProject {:project {:name "a" :public true}}}}
               (update-in response [:data :createProject :project] dissoc :id)))
        (is (string? project-1-id))
        (is (= {:data {:getProject {:id project-1-id :name "a" :public true}}}
               (execute! (sacq/get-project "id name public") {:id project-1-id}))))
      (testing "Projects cannot be created with blank names"
        (is (= ["Project name cannot be blank"]
               (->> (create-project! {:name "" :public true})
                    :errors (map :message))))
        (is (= ["Project name cannot be blank"]
               (->> (create-project! {:name " " :public true})
                    :errors (map :message)))))
      (testing "Anonymous users cannot create projects"
        (is (= ["Invalid API token"]
               (->> (api-test/execute! system (sacq/create-project "project {id}")
                                       {:input {:create {:name "a" :public true}}})
                    :body :errors (map :message)))))
      (testing "Made-up tokens cannot create projects"
        (is (= ["Invalid API token"]
               (->> (api-test/execute! system (sacq/create-project "project {id}")
                                       {:input {:create {:name "a" :public true}}}
                                       {:api-token (->> #(rand-nth "0123456789abcdef") (repeatedly 24) (apply str))})
                    :body :errors (map :message))))))))

(deftest ^:integration test-project
  (test/with-test-system [system {}]
    (let [api-token (:api-token (test/create-test-user system))
          api-token-2 (:api-token (test/create-test-user system))
          ex! (fn [query variables & [opts]]
                (ex! system query variables (merge {:api-token api-token} opts)))
          create-project! (fn [project & [opts]]
                            (-> (ex! (sacq/create-project "project {id}")
                                     {:input {:create project}}
                                     opts)
                                :data :createProject :project :id))
          project-1-id (create-project! {:name "a" :public true})]
      (testing "Non-existent ids return nil"
        (is (= {:data {:getProject nil}}
               (ex! (sacq/get-project "id") {:id "1"}))))
      (testing "project query returns the expected fields"
        (is (= {:data {:getProject {:id project-1-id :name "a" :public true}}}
               (ex! (sacq/get-project "id name public") {:id project-1-id}))))
      (testing "Users can see public projects belonging to other users"
        (let [project-2-id (create-project! {:name "2" :public true} {:api-token api-token-2})]
          (is (= {:data {:getProject {:id project-2-id :name "2" :public true}}}
                 (ex! (sacq/get-project "id name public") {:id project-2-id})))))
      (testing "Users can see private projects belonging to them"
        (let [project-3-id (create-project! {:name "3" :public false})]
          (is (= {:data {:getProject {:id project-3-id :name "3" :public false}}}
                 (ex! (sacq/get-project "id name public") {:id project-3-id})))))
      (testing "Users cannot see private projects belonging to other users"
        (let [project-4-id (create-project! {:name "4" :public false} {:api-token api-token-2})]
          (is (= {:data {:getProject nil}}
                 (ex! (sacq/get-project "id name public") {:id project-4-id}))))))))

(deftest ^:integration test-create-project-label!
  (test/with-test-system [system {}]
    (let [api-token (:api-token (test/create-test-user system))
          ex! (fn [query variables & [opts]]
                (ex! system query variables (merge {:api-token api-token} opts)))
          create-project! (fn [project & [opts]]
                            (-> (ex! (sacq/create-project "project {id}")
                                     {:input {:create project}}
                                     opts)
                                :data :createProject :project :id))
          project-1-id (create-project! {:name "a" :public true})
          create-project-label! (fn [project-id label & [opts]]
                                  (-> (ex! (sacq/create-project-label
                                            "projectLabel {consensus id name question type}")
                                           {:input {:create label
                                                    :projectId project-id}}
                                           opts)
                                      :data :createProjectLabel :projectLabel))
          label-1 (create-project-label! project-1-id
                                         {:name "x" :question "??" :type "BOOLEAN"})
          label-1-id (:id label-1)]
      (testing "Users can create projectLabels and retrieve them afterward"
        (is (= {:consensus false
                :name "x"
                :question "??"
                :type "BOOLEAN"}
               (dissoc label-1 :id)))
        (is (string? label-1-id))
        (is (= {:data {:getProjectLabel {:consensus false
                                         :id label-1-id
                                         :name "x"
                                         :question "??"
                                         :type "BOOLEAN"}}}
               (ex! (sacq/get-project-label "consensus id name question type") {:id label-1-id}))))
      (testing "Anonymous users cannot create projectLabels"
        (is (= ["Invalid API token"]
               (->> (api-test/execute! system (sacq/create-project-label "projectLabel {id}")
                                       {:input {:create {:name "x" :question "??" :type "BOOLEAN"}
                                                :projectId project-1-id}})
                    :body :errors (map :message)))))
      (testing "Made-up tokens cannot create projectLabels"
        (is (= ["Invalid API token"]
               (->> (api-test/execute! system (sacq/create-project-label "projectLabel {id}")
                                       {:input {:create {:name "x" :question "??" :type "BOOLEAN"}
                                                :projectId project-1-id}}
                                       {:api-token (->> #(rand-nth "0123456789abcdef") (repeatedly 24) (apply str))})
                    :body :errors (map :message)))))
      (testing "Users must be project admins to create projectLabels"
        (is (= ["Must be a project admin"]
               (->> (api-test/execute! system (sacq/create-project-label "projectLabel {id}")
                                       {:input {:create {:name "x" :question "??" :type "BOOLEAN"}
                                                :projectId project-1-id}}
                                       {:api-token (:api-token (test/create-test-user system))})
                    :body :errors (map :message))))
        ;; TODO: Test for a project member
        )
      (testing "ProjectLabels cannot be created with blank names, questions, or types"
        (is (= ["name field cannot be blank"]
               (->> (api-test/execute! system (sacq/create-project-label "projectLabel {id}")
                                       {:input {:create {:question "?" :type "BOOLEAN"}
                                                :projectId project-1-id}}
                                       {:api-token (:api-token (test/create-test-user system))})
                    :body :errors (map :message))))
        (is (= ["question field cannot be blank"]
               (->> (api-test/execute! system (sacq/create-project-label "projectLabel {id}")
                                       {:input {:create {:name "?" :type "BOOLEAN"}
                                                :projectId project-1-id}}
                                       {:api-token (:api-token (test/create-test-user system))})
                    :body :errors (map :message))))
        (is (= ["type field cannot be blank"]
               (->> (api-test/execute! system (sacq/create-project-label "projectLabel {id}")
                                       {:input {:create {:name "?" :question "?"}
                                                :projectId project-1-id}}
                                       {:api-token (:api-token (test/create-test-user system))})
                    :body :errors (map :message))))))))

(deftest ^:integration test-project-label
  (test/with-test-system [system {}]
    (let [api-token (:api-token (test/create-test-user system))
          api-token-2 (:api-token (test/create-test-user system))
          ex! (fn [query variables & [opts]]
                (ex! system query variables (merge {:api-token api-token} opts)))
          create-project! (fn [project & [opts]]
                            (-> (ex! (sacq/create-project "project {id}")
                                     {:input {:create project}}
                                     opts)
                                :data :createProject :project :id))
          project-1-id (create-project! {:name "a" :public true})
          create-project-label! (fn [project-id label & [opts]]
                                  (-> (ex! (sacq/create-project-label "projectLabel {id}")
                                           {:input {:create label
                                                    :projectId project-id}}
                                           opts)
                                      :data :createProjectLabel :projectLabel :id))
          label-1-id (create-project-label! project-1-id
                                            {:name "x" :question "??" :type "BOOLEAN"})]
      (testing "Non-existent ids return nil"
        (is (= {:data {:getProjectLabel nil}}
               (ex! (sacq/get-project-label "id name") {:id "1"}))))
      (testing "projectLabel query returns the expected fields"
        (is (= {:data {:getProjectLabel {:id label-1-id :name "x"}}}
               (ex! (sacq/get-project-label "id name") {:id label-1-id}))))
      (testing "Users can see labels belonging to public projects"
        (is (= {:data {:getProjectLabel {:id label-1-id :name "x"}}}
               (ex! (sacq/get-project-label "id name")
                    {:id label-1-id}
                    {:api-token api-token-2}))))
      (testing "Users can see labels belonging to their private projects"
        (let [project-3-id (create-project! {:name "3" :public false})
              label-3-id (create-project-label! project-3-id
                                                {:name "z" :question "??" :type "BOOLEAN"})]
          (is (= {:data {:getProjectLabel {:id label-3-id :name "z"}}}
                 (ex! (sacq/get-project-label "id name") {:id label-3-id})))))
      (testing "Users cannot see labels belonging to others' private projects"
        (let [project-4-id (create-project! {:name "4" :public false} {:api-token api-token-2})
              label-4-id (create-project-label! project-4-id
                                                {:name "a" :question "??" :type "BOOLEAN"}
                                                {:api-token api-token-2})]
          (is (= {:data {:getProjectLabel nil}}
                 (ex! (sacq/get-project-label "id name") {:id label-4-id}))))))))
