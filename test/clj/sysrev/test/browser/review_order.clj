(ns sysrev.test.browser.review-order
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.db.core :as db]
            [sysrev.label.core :as labels]
            [sysrev.project.core :as project]
            [sysrev.user.core :as user :refer [user-self-info]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]))

; test that recently assigned articles are moved to the end of the queue
(deftest-browser last-assigned-test (and (test/db-connected?) (not (test/remote-test?))) test-user []
  (do
    ; log in user 1
    (nav/log-in (:email test-user))
    ; create project
    (nav/new-project "Review order test")
    ; import some articles
    (pm/import-pmids-via-db [25706626 25215519 23790141 22716928 19505094 9656183])
    (nav/go-project-route "/review" :silent true :wait-ms 50)
    (b/wait-until-loading-completes :pre-wait 30 :loop 2)
    (b/click x/review-labels-tab :delay 25 :displayed? true)
    ; skip the articles
    (let [distinct-content
          (set (map (fn [_]
                      (b/click ".ui.button.skip-article")
                      (-> (taxi/element "div.ui.segment.article-content")
                          (taxi/text)))(range 0 6)))]
      (is (= (count distinct-content) 6))))
  :cleanup (do (nav/delete-current-project)
               (nav/log-out)
               (is (b/exists? "a#log-in-link"))))