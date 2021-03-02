(ns sysrev.test.etaoin.sources
  (:require [clojure.java.io :as io]
            [clojure.test :refer [is use-fixtures]]
            [medley.core :as medley]
            [me.raynes.fs :as fs]
            [sysrev.user.core :refer [user-by-email]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :as x]
            [sysrev.test.etaoin.core :as e :refer
             [*cleanup-users* deftest-etaoin etaoin-fixture]]
            [sysrev.test.etaoin.account :as account]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture)
(use-fixtures :each etaoin-fixture)

(deftest-etaoin pdf-files
  (let [user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        res-path "test-files/test-pdf-import"
        files (for [f (fs/list-dir (io/resource res-path))]
                (str res-path "/" (fs/base-name f)))]
    (e/new-project "pdf files test")
    (e/select-datasource "PDF files")
    (e/uppy-attach-files files)
    (e/click "//button[contains(text(),'Upload')]" :delay 200)
    (e/wait-exists {:css (b/not-disabled "div.delete-button")} 20000)
    (e/click {:css (x/project-menu-item :articles)} :delay 200)
    (e/click {:css "a.column.article-title"} :delay 200)
    (e/exists? {:css ".pdf-view .pdf-page-container .pdf-page"})
    (e/click {:css (x/project-menu-item :articles)} :delay 200)
    (e/exists? {:css "a.column.article-title"})))
