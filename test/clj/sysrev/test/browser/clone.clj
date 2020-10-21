(ns sysrev.test.browser.clone
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member]
            [sysrev.source.import :as import]
            [sysrev.user.core :as user]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.ctgov :as ctgov]
            [sysrev.test.browser.define-labels :as dlabels]
            [sysrev.test.browser.group-labels :as group-labels]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.pubmed :as pubmed]
            [sysrev.test.browser.search :as search]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def clone-button "button#clone-button")
(def clone-to-user "div#clone-to-user")
(def cloned-from (xpath "//span[contains(text(),'cloned from')]"))

(defn- unique-count-span [n]
  (format "span.unique-count[data-count='%d']" n))

(deftest-browser clone-project-happy-path
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "Sysrev Browser Test (clone-project-happy-path)"
   filename "test-pdf-import.zip"
   src-project-id (atom nil)]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! src-project-id (b/current-project-id))
    ;; import pdfs, check that the PDF count is correct
    ;; disabling this for now, having issues importing
    #_(import/import-pdf-zip @src-project-id {:file (-> (str "test-files/" filename) io/resource io/file)
                                              :filename filename}
                             {:use-future? false})
    (b/init-route (-> (taxi/current-url) b/url->path))
    #_(is (= 4 (project/project-article-pdf-count (b/current-project-id))))
    ;; import RIS file, check the RIS file citations is correct
    (b/click "#enable-import")
    (b/select-datasource "RIS / RefMan")
    (b/dropzone-upload "test-files/IEEE_Xplore_Citation_Download_LSTM_top_10.ris")
    (b/wait-until-exists (xpath "//div[contains(@class,'source-type') and contains(text(),'RIS file')]"))
    (is (b/exists? (unique-count-span 10)))
    ;; PubMed search input
    (b/select-datasource "PubMed")
    (pubmed/search-pubmed "foo bar")
    (b/click x/import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (b/exists? (unique-count-span 7)))
    ;; Import Clinical Trials
    (b/select-datasource "ClinicalTrials (beta)")
    (ctgov/search-ctgov "foo olive")
    (b/click x/import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (b/exists? (unique-count-span 2)))
    ;; Import from PMIDs file
    (b/select-datasource "PMID file")
    (b/dropzone-upload "test-files/pubmed_result.txt")
    (is (b/exists? (unique-count-span 7)))
    ;; import Endnote file
    (b/select-datasource "EndNote XML")
    (b/dropzone-upload "test-files/Endnote_3_citations.xml")
    (is (b/exists? (unique-count-span 3)))
    ;; When this project is cloned, everything is copied over correctly
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (b/click clone-button)
    (b/click clone-to-user)
    ;; this was an actual clone?
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (not= @src-project-id (b/current-project-id)))
    ;; check that all of the sources are consistent with the cloned project
    (b/click "a.manage")
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    ;; pdfs
    #_(is (= 4 (project/project-article-pdf-count (b/current-project-id))))
    ;; RIS
    (is (b/exists? (unique-count-span 10)))
    ;; PubMed search
    (is (b/exists? (unique-count-span 7)))
    ;; ctgov
    (is (b/exists? (unique-count-span 2)))
    ;; pmid file
    (is (b/exists? (unique-count-span 7)))
    ;; endnote
    (is (b/exists? (unique-count-span 3)))))

(deftest-browser clone-permissions-test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "Sysrev Browser Test (clone-permissions-test)"
   test-user-b (b/create-test-user :email "foo@qux.com" :password "foobar")
   src-project-id (atom nil)
   user-id (user/user-by-email (:email test-user) :user-id)]
  (do
    (plans/user-subscribe-to-unlimited (:email test-user))
    (nav/new-project project-name)
    (reset! src-project-id (b/current-project-id))
    ;; PubMed search input
    (b/click "#enable-import")
    (b/select-datasource "PubMed")
    (pubmed/import-pubmed-search-via-db "foo bar")
    (is (b/exists? (unique-count-span 7)))
    ;; user can clone their project
    (b/exists? clone-button)
    ;; another user can also clone the project
    (nav/log-in (:email test-user-b) (:password test-user-b))
    (search/search-for project-name)
    (b/click (xpath (str "//h3[contains(text(),'" project-name "')]")))
    (b/exists? clone-button)
    ;; test-user-b joins the project
    (member/add-project-member @src-project-id (:user-id test-user-b))
    (is (not (:public-access (project/change-project-setting
                              @src-project-id :public-access false))))
    ;; refresh the browser, the clone button should be gone
    (b/init-route (-> (taxi/current-url) b/url->path))
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (not (taxi/exists? clone-button))))
  :cleanup (b/cleanup-test-user! :email (:email test-user-b)))

(deftest-browser clone-login-redirect
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "Sysrev Browser Test (clone-login-redirect)"
   test-user-b {:email (format "foo+%s@qux.com" (util/random-id))
                :password "foobar"}
   create-account-div (xpath "//h3[contains(text(),'First, create an account to clone the project')]")]
  ;; first, login create the test project
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (nav/log-out)
    (search/search-for project-name)
    (b/click (xpath (str "//h3[contains(text(),'" project-name "')]")))
    (b/click clone-button)
    ;; redirected to create account
    (is (b/exists? create-account-div))
    (b/set-input-text "input[name='email']" (:email test-user-b))
    (b/set-input-text "input[name='password']" (:password test-user-b))
    (b/click "button[name='submit']")
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (b/click clone-to-user))
  :cleanup (b/cleanup-test-user! :email (:email test-user-b)))

(deftest-browser group-label-clone-test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-label-clone-test)"
   project-id (atom nil)
   include-label-value true
   group-label-definition {:value-type "group"
                           :short-label "Group Label"
                           :definition
                           {:labels [{:value-type "boolean"
                                      :short-label "Boolean Label"
                                      :question "Is this true or false?"
                                      :definition {:inclusion-values [true]}
                                      :required true
                                      :value true}
                                     {:value-type "string"
                                      :short-label "String Label"
                                      :question "What value is present for Foo?"
                                      :definition
                                      {:max-length 160
                                       :examples ["foo" "bar" "baz" "qux"]
                                       :multi? true}
                                      :required true
                                      :value "Baz"}
                                     {:value-type "categorical"
                                      :short-label "Categorical Label"
                                      :question "Does this label fit within the categories?"
                                      :definition
                                      {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                       :inclusion-values ["Foo" "Bar"]
                                       :multi? false}
                                      :required true
                                      :value "Qux"}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    ;; import article
    (import/import-pmid-vector @project-id {:pmids [25706626]})
    ;; create new labels
    (log/info "Creating Group Label Definitions")
    (nav/go-project-route "/labels/edit")
    (dlabels/define-group-label group-label-definition)
    ;; make sure the labels are in the correct order
    (is (= (mapv :short-label (-> group-label-definition :definition :labels))
           (group-labels/group-sub-short-labels "Group Label")))
    ;; clone this project
    (nav/go-project-route "")
    (b/click clone-button)
    (b/click clone-to-user)
    ;; make sure this is actually a clone
    (is (b/exists? (xpath cloned-from)))
    ;; check that the labels are correct
    (nav/go-project-route "/labels/edit")
    (is (= (mapv :short-label (->> group-label-definition :definition :labels))
           (group-labels/group-sub-short-labels "Group Label")))))
