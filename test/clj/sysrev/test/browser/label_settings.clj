(ns sysrev.test.browser.label-settings
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure-csv.core :as csv]
            [clj-webdriver.taxi :as taxi]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.db.export :as export]
            [sysrev.source.import :as import]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]
            [sysrev.shared.util :as sutil :refer [in?]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser label-consensus-test
  (test/db-connected?)
  [separator export/default-csv-separator
   project-id (atom nil)
   label-id-1 (atom nil)
   test-users (mapv #(str "user" % "@fake.com") [1 2 3])
   [user1 user2 user3] test-users
   to-user-name #(-> % (str/split #"@") first)
   project-name "Label Consensus Test"
   switch-user (fn [email]
                 (nav/log-in email)
                 (nav/go-project-route "" :project-id @project-id :silent true :wait-ms 50))
   label1 {:value-type "categorical"
           :short-label "Test Label 1"
           :question "Is it?"
           :definition {:all-values ["One" "Two" "Three"]
                        :inclusion-values ["One"]
                        :multi? true}
           :required false}
   all-defs [review/include-label-definition label1]
   lvalues-1 [(merge review/include-label-definition {:value true})
              (merge label1 {:value "One"})]
   lvalues-2 [(merge review/include-label-definition {:value true})
              (merge label1 {:value "Two"})]
   include-full ".label-status-help .include-full-button"
   conflicts ".label-status-help .conflict-button"
   resolved ".label-status-help .resolve-button"
   check-status (fn [n-full n-conflict n-resolved]
                  (nav/go-project-route "" :silent true :wait-ms 50 :pre-wait-ms 50)
                  (is (b/exists? include-full))
                  (b/is-soon (= (format "Full (%d)" n-full) (taxi/text include-full)))
                  (is (b/exists? conflicts))
                  (b/is-soon (= (format "Conflict (%d)" n-conflict) (taxi/text conflicts)))
                  (is (b/exists? resolved))
                  (b/is-soon (= (format "Resolved (%d)" n-resolved) (taxi/text resolved)))
                  (b/wait-until-loading-completes :pre-wait true))]
  (do (nav/log-in)
      ;; create project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (assert (integer? @project-id))
      ;; import one article
      (import/import-pmid-vector @project-id {:pmids [25706626]} {:use-future? false})
      (nav/go-project-route "/manage")  ; test "Manage" button
      ;; create a categorical label
      (define/define-label label1)
      (is (b/exists? (x/match-text "span" (:short-label label1))))
      ;; create users
      (doseq [email test-users]
        (let [{:keys [user-id]} (b/create-test-user :email email :project-id @project-id)]
          (assert (integer? user-id))
          ;; set "admin" on user1 for editing labels
          (when (in? [user1] email)
            (project/set-member-permissions
             @project-id user-id ["member" "admin"]))))
      ;; review article from user1
      (switch-user user1)
      (nav/go-project-route "/review")
      (review/set-article-answers lvalues-1)
      (let [uanswers (export/export-user-answers-csv @project-id)
            [_ u1] uanswers
            ganswers (export/export-group-answers-csv @project-id)
            [_ g1] ganswers]
        (is (= 1 (-> uanswers rest count)))
        (is (in? u1 "true"))
        (is (in? u1 (to-user-name user1)))
        (is (in? u1 "One"))
        (is (= 1 (-> ganswers rest count)))
        (is (in? g1 "true"))
        (is (in? g1 "One"))
        (is (in? g1 "single")) ;; consensus status
        (is (in? g1 "1"))      ;; user count
        (is (in? g1 (to-user-name user1)))
        (is (= uanswers (-> uanswers csv/write-csv (csv/parse-csv :strict true))))
        (is (= ganswers (-> ganswers csv/write-csv (csv/parse-csv :strict true)))))
      ;; review article from user2 (different categorical answer)
      (switch-user user2)
      (nav/go-project-route "/review")
      (review/set-article-answers lvalues-2)
      (is (b/exists? ".no-review-articles"))
      ;; check for no conflict
      (check-status 1 0 0)
      (let [uanswers (export/export-user-answers-csv @project-id)
            ganswers (export/export-group-answers-csv @project-id)
            [_ g1] ganswers]
        (is (= 2 (-> uanswers rest count)))
        (is (= 1 (-> ganswers rest count)))
        (is (in? g1 "true"))
        (let [values ["One" "Two"]]
          (is (or (in? g1 (str/join separator values))
                  (in? g1 (str/join separator (reverse values))))))
        (is (in? g1 "consistent")) ;; consensus status
        (is (in? g1 "2"))          ;; user count
        (let [names (map to-user-name [user1 user2])]
          (is (or (in? g1 (str/join separator names))
                  (in? g1 (str/join separator (reverse names))))))
        (is (= uanswers (-> uanswers csv/write-csv (csv/parse-csv :strict true))))
        (is (= ganswers (-> ganswers csv/write-csv (csv/parse-csv :strict true)))))
      ;; enable label consensus setting
      (switch-user user1)
      (reset! label-id-1 (->> (vals (project/project-labels @project-id))
                              (filter #(= (:short-label %)
                                          (:short-label label1)))
                              first :label-id))
      (assert @label-id-1)
      (define/edit-label @label-id-1 (merge label1 {:consensus true}))
      ;; check that article now shows as conflict
      (check-status 0 1 0)
      (let [uanswers (export/export-user-answers-csv @project-id)
            ganswers (export/export-group-answers-csv @project-id)
            [_ g1] ganswers]
        (is (= 2 (-> uanswers rest count)))
        (is (= 1 (-> ganswers rest count)))
        (is (in? g1 "true"))
        (let [values ["One" "Two"]]
          (is (or (in? g1 (str/join separator values))
                  (in? g1 (str/join separator (reverse values))))))
        (is (in? g1 "conflict")) ;; consensus status
        (is (in? g1 "2"))        ;; user count
        (let [names (map to-user-name [user1 user2])]
          (is (or (in? g1 (str/join separator names))
                  (in? g1 (str/join separator (reverse names))))))
        (is (= uanswers (-> uanswers csv/write-csv (csv/parse-csv :strict true))))
        (is (= ganswers (-> ganswers csv/write-csv (csv/parse-csv :strict true)))))
      ;; switch to non-admin user to use "Change Labels"
      (switch-user user2)
      ;; check article list interface (Conflict filter)
      (check-status 0 1 0)
      (b/click conflicts :displayed? true :delay 100)
      (b/click "div.article-list-article")
      ;; check for conflict label in article component
      (is (b/exists? ".label.review-status.orange"))
      ;; change answers (remove value for categorical label)
      (b/click ".button.change-labels")
      (b/click ".label-edit .dropdown a.label i.delete.icon")
      (b/click ".button.save-labels" :delay 25)
      (nav/go-project-route "/articles")
      ;; check that article still shows as conflict
      (check-status 0 1 0)
      ;; disable label consensus setting
      (switch-user user1)
      (define/edit-label @label-id-1 (merge label1 {:consensus false}))
      ;; check that article no longer shows as conflict
      (check-status 1 0 0)
      ;; check article list interface (Include Full filter)
      (b/click include-full :delay 100)
      (is (b/exists? "div.article-list-article"))
      (nav/go-project-route "/articles")
      ;; re-enable label consensus setting
      (define/edit-label @label-id-1 (merge label1 {:consensus true}))
      ;; check that articles shows as conflict again
      (check-status 0 1 0)
      ;; resolve article conflict
      (b/click conflicts :delay 100)
      (b/click "div.article-list-article")
      (is (b/exists? ".button.change-labels"))
      (is (= "Resolve Labels" (taxi/text ".button.change-labels")))
      (b/click ".button.change-labels")
      (b/click ".button.save-labels" :delay 25)
      (nav/go-project-route "/articles")
      ;; check that article is resolved
      (check-status 1 0 1)
      (let [uanswers (export/export-user-answers-csv @project-id)
            ganswers (export/export-group-answers-csv @project-id)
            [_ g1] ganswers]
        (is (= 2 (-> uanswers rest count)))
        (is (= 1 (-> ganswers rest count)))
        (is (in? g1 "true"))
        (is (in? g1 "One"))
        (is (in? g1 "resolved")) ;; consensus status
        (is (in? g1 "2"))        ;; user count
        (let [names (map to-user-name [user1 user2])]
          (is (or (in? g1 (str/join separator names))
                  (in? g1 (str/join separator (reverse names))))))
        (is (= uanswers (-> uanswers csv/write-csv (csv/parse-csv :strict true))))
        (is (= ganswers (-> ganswers csv/write-csv (csv/parse-csv :strict true)))))
      ;; check article list interface (Resolved filter)
      (b/click resolved :delay 100)
      (is (b/exists? "div.article-list-article"))
      (b/click "div.article-list-article")
      ;; check for resolved labels in article component
      (is (b/exists? ".ui.label.review-status.purple"))
      (is (b/exists? ".ui.label.labels-status.purple")))
  :cleanup
  (do (some-> @project-id (project/delete-project))
      (doseq [email test-users] (b/delete-test-user :email email))))
