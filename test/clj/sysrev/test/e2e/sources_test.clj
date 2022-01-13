(ns sysrev.test.e2e.sources-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [me.raynes.fs :as fs]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.xpath :as x]))

(deftest ^:e2e pdf-files
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (testing "PDF imports work"
      (let [user (test/create-test-user system)
            res-path "test-files/test-pdf-import"
            files (for [f (fs/list-dir (io/resource res-path))]
                    (str res-path "/" (fs/base-name f)))]
        (doto test-resources
          (account/log-in user)
          (e/new-project "pdf files test"))
        (doto driver
          (e/select-datasource "PDF files")
          (e/uppy-attach-files files)
          (et/is-click-visible "//button[contains(text(),'Upload')]")
          (et/is-wait-exists {:css (str "div.delete-button:" e/not-disabled)})
          (et/is-click-visible {:css (x/project-menu-item :articles)})
          (et/is-click-visible {:css "a.column.article-title"})
          (et/is-wait-exists {:css ".pdf-view .pdf-page-container .pdf-page"})
          (et/is-click-visible {:css (x/project-menu-item :articles)} )
          (et/is-wait-exists {:css "a.column.article-title"}))))))

(deftest ^:kaocha/pending ^:e2e test-pdf-interface)

(deftest ^:kaocha/pending ^:e2e test-import-ris-file)
