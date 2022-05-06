(ns sysrev.project-api.source-test
  (:require [clojure.test :refer :all]
            [sysrev.project.core :as project]
            [sysrev.source.files :as files]
            [sysrev.sysrev-api-client.interface.queries :as sacq]
            [sysrev.sysrev-api.test :as api-test]
            [sysrev.test.core :as test]))

(defn ex! [system query & [variables opts]]
  (-> (api-test/execute! system query variables opts)
      :body
      api-test/throw-errors))

(deftest ^:integration test-create-project-source!
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [api-token]} (test/create-test-user system)
          project-id (-> (ex! system (sacq/create-project "project{id}")
                              {:input {:create {:name "a"}}}
                              {:api-token api-token})
                         :data :createProject :project :id)
          project-int-id (parse-long project-id)
          dev-key (-> system :config :sysrev-dev-key)
          dataset-id "1"
          dex! (fn [query variables]
                 (ex! system query variables {:api-token dev-key}))
          source-id (-> (sacq/create-project-source "projectSource{id}")
                        (dex! {:input {:create {:datasetId dataset-id
                                                :projectId project-id}}})
                        :data :createProjectSource :projectSource :id)]
      (is (string? source-id)
          "createProjectSource works")
      (is (= "Dataset does not exist"
             (-> (api-test/execute!
                  system
                  (sacq/create-project-source "projectSource{id}")
                  {:input {:create {:datasetId (str (random-uuid))
                                    :projectId project-id}}}
                  {:api-token dev-key})
                 :body :errors first :message))
          "Can't create a source for a non-existent Dataset")
      (is (= "Project does not exist"
             (-> (api-test/execute!
                  system
                  (sacq/create-project-source "projectSource{id}")
                  {:input {:create {:datasetId dataset-id
                                    :projectId (str (random-uuid))}}}
                  {:api-token dev-key})
                 :body :errors first :message))
          "Can't create a source for a non-existent Project")
      (files/import-from-job-queue! sr-context)
      (is (test/wait-not-importing? system project-int-id))
      (is (= 11 (project/project-article-count project-int-id))))))
