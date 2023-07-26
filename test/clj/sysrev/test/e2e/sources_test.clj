(ns sysrev.test.e2e.sources-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test]
            [sysrev.test.e2e.account :as account]
            [sysrev.test.e2e.core :as e]
            [sysrev.test.e2e.project :as e-project]))

(deftest ^:e2e test-pdf-interface
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [{:keys [sr-context]} system
          project-id (e-project/create-project! test-resources "test-pdf-interface")]
      (src/import-source
       sr-context :pdf-zip project-id
       {:file (io/file (io/resource "test-files/test-pdf-import.zip"))
        :filename "test-pdf-import.zip"}
       {:use-future? false})
      (ea/refresh driver)
      (e/go-project test-resources project-id "/articles")
      (doto driver
        (et/is-click-visible {:css "a.column.article-title"})
        (et/is-wait-exists "//iframe[@title='webviewer']")))))
