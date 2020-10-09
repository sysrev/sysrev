(ns sysrev.test.browser.review-order
  (:require [clojure.test :refer [is use-fixtures]]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.xpath :as x]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;; test that recently assigned articles are moved to the end of the queue
(deftest-browser last-assigned-test
  (and (test/db-connected?) (not (test/remote-test?)))
  test-user
  [project-name "last-assigned-test"]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (pm/import-pmids-via-db [25706626 25215519 23790141 22716928 19505094 9656183])
      (nav/go-project-route "/review")
      (b/click x/review-labels-tab :delay 50 :displayed? true)
      ;; check that no articles are repeated when clicking "Skip"
      (is (= 6 ((comp count distinct)
                (for [_ (range 6)]
                  (do (b/click ".ui.button.skip-article")
                      (taxi/text ".ui.segment.article-content"))))))))
