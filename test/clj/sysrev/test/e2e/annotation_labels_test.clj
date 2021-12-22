(ns sysrev.test.e2e.annotation-labels-test
  (:require
   [clojure.test :refer :all]
   [sysrev.api :as api]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.labels :as labels]))

(deftest ^:e2e test-annotation-labels
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [annotation-label-definition {:value-type "annotation"
                                       :short-label "Test Label 1"
                                       :question "Is it?"
                                       :definition {:all-values ["EntityOne" "EntityTwo" "EntityThree"]}
                                       :required false}
          user (test/create-test-user)
          project (:project
                   (api/create-project-for-user!
                    "Browser Test (annotation labels)" (:user-id user) true))]
      (doto test-resources
        (account/log-in user)
        (labels/define-label (:project-id project) annotation-label-definition)))))
