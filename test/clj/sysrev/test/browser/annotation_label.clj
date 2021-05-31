(ns sysrev.test.browser.annotation-label
  (:require [clojure.string :as str]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.define-labels :as define]
            [sysrev.util :as util]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser test-annotation-labels
  (test/db-connected?) test-user
  [project-name "Browser Test (annotation labels)"
   project-id (atom nil)
   annotation-label-definition {:value-type "annotation"
                                :short-label "Test Label 1"
                                :question "Is it?"
                                :definition {:all-values ["EntityOne" "EntityTwo" "EntityThree"]}
                                :required false}]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (define/define-label annotation-label-definition)))
