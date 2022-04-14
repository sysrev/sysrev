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

(deftest ^:e2e test-import-ris-file
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [project-id (e-project/create-project! test-resources "test-import-ris-file")]
      (e/go-project test-resources project-id "/add-articles")
      (testing "RIS file upload works"
        (doto driver
          (et/is-click-visible {:css ".datasource-item[data-datasource='ris-file']"})
          (e/dropzone-upload "test-files/IEEE_Xplore_Citation_Download_LSTM_top_10.ris")
          (et/is-wait-visible "//div[contains(@class,'source-type') and contains(text(),'RIS file')]")
          (et/is-wait-visible {:css ".unique-count[data-count='10']"})))
      (e/go-project test-resources project-id "/articles")
      (doto driver
        (et/is-click-visible {:fn/has-class :article-title
                              :fn/text "Long Short-Term Memory"})
        (et/is-wait-visible {:fn/has-text "Learning to store information over extended time intervals"})))))
