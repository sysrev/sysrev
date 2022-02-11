(ns sysrev.sysrev-api.project-test
  (:require
   [clojure.test :refer :all]
   [sysrev.sysrev-api-client.interface.queries :as sacq]
   [sysrev.sysrev-api.test :as api-test]
   [sysrev.test.core :as test]))

(defn ex! [system query & [variables opts]]
  (-> (api-test/execute! system query variables opts)
      :body
      api-test/throw-errors))

(deftest ^:integration test-createProject!
  (test/with-test-system [system {}]
    (let [api-token (:api-token (test/create-test-user system))
          execute! #(:body (api-test/execute! system % %2 {:api-token api-token}))
          create-project! (fn [input]
                            (execute! (sacq/m-create-project "id name public")
                                      {:input input}))
          response (create-project! {:name "a" :public true})
          project-1-id (get-in response [:data :createProject :id])]
      (testing "Users can create projects and retrieve them afterward"
        (is (= {:data {:createProject {:name "a" :public true}}}
               (update-in response [:data :createProject] dissoc :id)))
        (is (string? project-1-id))
        (is (= {:data {:project {:id project-1-id :name "a" :public true}}}
               (execute! (sacq/q-project "id name public") {:id project-1-id}))))
      (testing "Projects cannot be created with blank names"
        (is (= ["Project name cannot be blank"]
               (->> (create-project! {:name "" :public true})
                    :errors (map :message))))
        (is (= ["Project name cannot be blank"]
               (->> (create-project! {:name " " :public true})
                    :errors (map :message)))))
      (testing "Anonymous users cannot create projects"
        (is (= ["Invalid API token"]
               (->> (api-test/execute! system (sacq/m-create-project "id")
                                       {:input {:name "a" :public true}})
                    :body :errors (map :message)))))
      (testing "Made-up tokens cannot create projects"
        (is (= ["Invalid API token"]
               (->> (api-test/execute! system (sacq/m-create-project "id")
                                       {:input {:name "a" :public true}}
                                       {:api-token (->> #(rand-nth "0123456789abcdef") (repeatedly 24) (apply str))})
                    :body :errors (map :message))))))))

(deftest ^:integration test-project
  (test/with-test-system [system {}]
    (let [api-token (:api-token (test/create-test-user system))
          api-token-2 (:api-token (test/create-test-user system))
          ex! (fn [query variables & [opts]]
                (ex! system query variables (merge {:api-token api-token} opts)))
          create-project! (fn [input & [opts]]
                            (-> (ex! (sacq/m-create-project "id")
                                     {:input input}
                                     opts)
                                :data :createProject :id))
          project-1-id (create-project! {:name "a" :public true})]
      (testing "Non-existent ids return nil"
        (is (= {:data {:project nil}}
               (ex! (sacq/q-project "id") {:id "1"}))))
      (testing "project query returns the expected fields"
        (is (= {:data {:project {:id project-1-id :name "a" :public true}}}
               (ex! (sacq/q-project "id name public") {:id project-1-id}))))
      (testing "Users can see public projects belonging to other users"
        (let [project-2-id (create-project! {:name "2" :public true} {:api-token api-token-2})]
          (is (= {:data {:project {:id project-2-id :name "2" :public true}}}
                 (ex! (sacq/q-project "id name public") {:id project-2-id})))))
      (testing "Users can see private projects belonging to them"
        (let [project-3-id (create-project! {:name "3" :public false})]
          (is (= {:data {:project {:id project-3-id :name "3" :public false}}}
                 (ex! (sacq/q-project "id name public") {:id project-3-id})))))
      (testing "Users cannot see private projects belonging to other users"
        (let [project-4-id (create-project! {:name "4" :public false} {:api-token api-token-2})]
          (is (= {:data {:project nil}}
                 (ex! (sacq/q-project "id name public") {:id project-4-id}))))))))
