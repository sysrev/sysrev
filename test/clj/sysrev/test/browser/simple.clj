(ns sysrev.test.browser.simple
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [clojure.string :as str]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn root-panel-exists? []
  (b/is-soon (some #(nav/panel-exists? % :wait? false)
                   [[:project :project :overview]
                    [:project :project :add-articles]])))

(deftest-browser project-routes
  true []
  (do (nav/log-in)
      (nav/new-project "Simple Test")
      (let [project-id (b/current-project-id)]
        #_ (pm/add-articles-from-search-term "foo bar")
        (pm/import-pubmed-search-via-db "foo bar")
        (nav/go-project-route "")
        (is (nav/panel-exists? [:project]))
        (root-panel-exists?)
        (is (not (nav/panel-exists? [:project :project :fake-panel]
                                    :wait? false)))

        (nav/go-project-route "/labels/edit")
        (is (nav/panel-exists? [:project :project :labels :edit]))

        (nav/go-project-route "/settings")
        (is (nav/panel-exists? [:project :project :settings]))

        (nav/go-project-route "/export")
        (is (nav/panel-exists? [:project :project :export-data]))

        (nav/go-project-route "/review")
        (is (nav/panel-exists? [:project :review]))

        (nav/go-project-route "/articles")
        (is (nav/panel-exists? [:project :project :articles]))

        (nav/go-project-route "")
        (root-panel-exists?)

        (when project-id
          (nav/go-project-route "/settings" :project-id project-id))

        (is (b/exists? {:css "a#log-out-link"}))))

  :cleanup
  (do (nav/delete-current-project)
      (nav/log-out)
      (is (b/exists? "div#login-register-panel"))))
