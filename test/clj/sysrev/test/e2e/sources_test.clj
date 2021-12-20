(ns sysrev.test.e2e.sources-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [me.raynes.fs :as fs]
   [sysrev.test.xpath :as x]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e pdf-files
  (e/with-test-resources [{:keys [driver system] :as test-resources}]
    (testing "PDF imports work"
      (let [user (test/create-test-user)
            res-path "test-files/test-pdf-import"
            files (for [f (fs/list-dir (io/resource res-path))]
                    (str res-path "/" (fs/base-name f)))]
        (doto test-resources
          (account/log-in user)
          (e/new-project "pdf files test"))
        (doto driver
          (e/select-datasource "PDF files")
          (e/uppy-attach-files files)
          (e/click-visible "//button[contains(text(),'Upload')]")
          (e/wait-exists {:css (str "div.delete-button:" e/not-disabled)} 20000)
          (e/click-visible {:css (x/project-menu-item :articles)})
          (e/click-visible {:css "a.column.article-title"})
          (e/exists? {:css ".pdf-view .pdf-page-container .pdf-page"})
          (e/click-visible {:css (x/project-menu-item :articles)} )
          (e/exists? {:css "a.column.article-title"}))))))
