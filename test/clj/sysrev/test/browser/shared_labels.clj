(ns sysrev.test.browser.shared-labels
  (:require [clojure.string :as str]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test]
            ;; TODO: uncomment when addressing new labels UI tests
            [sysrev.test.browser.core :as b ;:refer [deftest-browser]
             ]
            ;[sysrev.test.browser.navigate :as nav]
            ;[sysrev.test.browser.define-labels :as define]
            ;[sysrev.test.browser.pubmed :as pm]
            ;[sysrev.test.browser.xpath :as x :refer [xpath]]
            ;[sysrev.util :as util]
            ))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;; TODO: Update tests to new UI
#_(deftest-browser test-shared-labels
  (test/db-connected?) test-user
  [{:keys [user-id email]} test-user
   project-name-1 "Sysrev Browser Test (shared-labels-src-project)"
   project-name-2 "Sysrev Browser Test (shared-labels-dst-project)"
   click-project-link #(do (log/infof "loading project %s" (pr-str %))
                           (b/click (xpath "//a[contains(text(),'" % "')]")))
   label-maps [{:value-type "categorical"
                :short-label "Test Label 1"
                :question "Is it?"
                :definition {:all-values ["One" "Two" "Three"]
                             :inclusion-values ["One"]
                             :multi? true}
                :required false}]
   share-codes (atom {})]
  (do (b/start-webdriver true)
      (nav/log-in email)
      (nav/new-project project-name-1)
      (pm/import-pubmed-search-via-db "foo bar")

      (doseq [label-map label-maps]
        (define/define-label label-map) 
        (swap! share-codes assoc (:short-label label-map) (define/get-share-code (define/get-label-id (b/current-project-id) label-map))))

      (nav/new-project project-name-2)
      (pm/import-pubmed-search-via-db "foo bar")

      (doseq [label-map label-maps]
        (define/import-label (get @share-codes (:short-label label-map)))
        (b/wait-until-displayed (xpath (str "//span[contains(@class,'short-label') and contains(text(),'" (:short-label label-map) "')]"))))))
