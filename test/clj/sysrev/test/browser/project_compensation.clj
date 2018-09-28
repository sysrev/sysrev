(ns sysrev.test.browser.project-compensation
  (:require [clojure.test :refer :all]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.helpers :as sqlh :refer [select from where join delete-from]]
            [sysrev.api :as api]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.test.browser.core :as browser :refer [deftest-browser]]
            [sysrev.test.browser.create-project :as create-project]
            [sysrev.test.browser.navigate :refer [log-in log-out]]
            [sysrev.test.browser.review-articles :as review-articles]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

(defn delete-project-compensation!
  "Delete a project compensation and all the associated data with it. Note: this is a dangerous function that should only be used in a test environment. In no circumstance should compensation be delete in production!"
  [project-id amount]
  (let [compensation-id (-> (select :*)
                            (from [:compensation_project :cp])
                            (join [:compensation :c]
                                  [:= :c.id :cp.compensation_id])
                            (where [:and
                                    [:= :cp.project_id project-id]])
                            do-query
                            (->> (filterv #(= (get-in % [:rate :amount]) amount)))
                            first
                            :id)]
    ;; delete from compensation-user-period
    (-> (delete-from :compensation_user_period)
        (where [:= :compensation_id compensation-id])
        do-execute)
    ;; delete from compensation-project-default
    (-> (delete-from :compensation_project_default)
        (where [:= :compensation_id compensation-id])
        do-execute)
    ;; delete from compensation-project
    (-> (delete-from :compensation_project)
        (where [:= :compensation_id compensation-id])
        do-execute)
    ;; delete from compensation
    (-> (delete-from :compensation)
        (where [:= :id compensation-id])
        do-execute)))

(defn cents->string
  "Convert an integer amount of cents to a string dollar amount"
  [cents]
  (->> (/ cents 100) double (format "%.2f")))

(defn create-compensation
  "Create a compensation in an integer amount of cents"
  [amount]
  (let [compensations {:xpath "//"}
        create-new-compensation {:xpath "//h4[contains(text(),'Create New Compensation')]"}
        amount-input {:xpath "//input[@type='text']"}
        amount-create {:xpath "//button[contains(text(),'Create')]"}]

    (log/info (str "Creating a compensation of " amount " cents"))
    (browser/go-project-route "/compensations")
    (browser/wait-until-exists create-new-compensation)
    (browser/set-input-text-per-char amount-input (cents->string amount))
    (browser/click amount-create)
    (browser/wait-until-exists {:xpath (str "//div[contains(text(),'$" (cents->string amount) " per Article')]")})))

(defn compensation-select-xpath-string
  [user]
  (str "//div[contains(text(),'" user "')]/ancestor::div[contains(@class,'item')]/descendant::div[@role='listbox']"))

(defn compensation-option
  [user amount]
  (let [amount (if (number? amount)
                 (str "$" (cents->string amount) " / article")
                 "No Compensation")]
    (str (compensation-select-xpath-string user)
         "/descendant::div[@role='option']/span[@class='text' and text()='" amount  "']")))

(defn project-name-xpath-string
  [project-name]
  (str "//span[contains(@class,'project-title') and text()='" project-name "']"))

(defn select-compensation-for-user
  "Amount can be 'No Compensation' or integer amount of cents"
  [user amount]
  (browser/click {:xpath (compensation-select-xpath-string user)})
  (browser/click {:xpath (compensation-option user amount)}))

(defn todays-date
  []
  ;; YYYY-MM-DD
  (f/unparse (f/formatter :date) (l/local-now)))

(defn amount-owed-user-by-project
  [project-id user-name]
        ;; check that they are the compensation is correct
      (let [amount-owed (get-in (api/amount-owed project-id
                                                 (todays-date)
                                                 (todays-date))
                                [:result :amount-owed])
            owed-to-user (->> amount-owed
                              (filter #(= (:name %)
                                          user-name))
                              ;;first
                              (map #(* (:articles %)
                                       (get-in % [:rate :amount])))
                              (apply +))]
        #_(* (:articles owed-to-user)
           (get-in owed-to-user [:rate :amount]))
        owed-to-user))

(deftest-browser happy-path-project-compensation
  (let [project-name "SysRev Compensation Test"
        search-term "foo create"
        first-compensation-amount 100
        test-user {:name "foo"
                   :email "foo@bar.com"
                   :password "foobar"}
        n 10]
    (try
      ;; create a project
      (log-in)
      (create-project/create-project project-name)
      ;; import sources
      (create-project/add-articles-from-search-term search-term)
      ;; create a compensation level
      (create-compensation first-compensation-amount)
      ;; set it to default
      (select-compensation-for-user "Default New User Compensation" 100)
      ;; create a new user, check that that their compensation level is set to the default
      (browser/create-test-user :email (:email test-user)
                                :password (:password test-user)
                                :project-id (browser/current-project-id))
      ;; new user reviews some articles
      (log-out)
      (log-in (:email test-user) (:password test-user))
      (browser/click {:xpath (project-name-xpath-string project-name)})
      (review-articles/randomly-review-n-articles n [(merge review-articles/include-label-definition {:all-values [true false]} )])
      (is (= (* n first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name test-user))))
      (finally
        (log-out)
        (log-in)
        ;; also delete all compensations
        (browser/click {:xpath (project-name-xpath-string project-name)})
        (delete-project-compensation! (browser/current-project-id) first-compensation-amount)
        (create-project/delete-current-project)
        (log-out)
        (browser/delete-test-user :email (:email test-user))))))

(deftest-browser multiple-project-compensations
  (let [first-project-name "SysRev Compensation Test 1"
        first-project-first-compensation-amount 100
        first-project-second-compensation-amount 10
        first-project-third-compensation-amount 110
        first-project-search-term "foo create"
        second-project-name "SysRev Compensation Test 2"
        second-project-first-compensation-amount 100
        second-project-second-compensation-amount 20
        second-project-third-compensation-amount 330
        second-project-search-term "foo create"
        first-test-user {:name "foo"
                         :email "foo@bar.com"
                         :password "foobar"
                         :article-amount 2}
        second-test-user {:name "baz"
                          :email "baz@qux.com"
                          :password "bazqux"
                          :article-amount 3}
        third-test-user {:name "corge"
                         :email "corge@grault.com"
                         :password "corgegrault"
                         :article-amount 6}
        label-definitions [(merge review-articles/include-label-definition {:all-values [true false]})
                           (merge review-articles/categorical-label-definition
                                  {:all-values (get-in review-articles/categorical-label-definition [:definition :all-values])})
                           (merge review-articles/boolean-label-definition
                                  {:all-values [true false]})]]
    (try
      ;; login
      (log-in)
      ;; create the first project
      (create-project/create-project first-project-name)
      ;; import sources
      (create-project/add-articles-from-search-term first-project-search-term)
      ;; create two additional labels
      (browser/click review-articles/label-definitions-tab)
      ;; create a boolean label
      (browser/click review-articles/add-boolean-label-button)
      (review-articles/set-label-values "//div[contains(@id,'new-label-')]" review-articles/boolean-label-definition)
      (review-articles/save-label)
      ;; create a categorical label
      (browser/click review-articles/add-categorical-label-button)
      (review-articles/set-label-values "//div[contains(@id,'new-label-')]" review-articles/categorical-label-definition)
      (review-articles/save-label)
      ;; create three compensations
      (create-compensation first-project-first-compensation-amount)
      (create-compensation first-project-second-compensation-amount)
      (create-compensation first-project-third-compensation-amount)
      ;; set the first compensation amount to the default
      (select-compensation-for-user "Default New User Compensation" first-project-first-compensation-amount)
      ;; create three additional users
      (browser/create-test-user :email (:email first-test-user)
                                :password (:password first-test-user)
                                :project-id (browser/current-project-id))
      (browser/create-test-user :email (:email second-test-user)
                                :password (:password second-test-user)
                                :project-id (browser/current-project-id))
      (browser/create-test-user :email (:email third-test-user)
                                :password (:password third-test-user)
                                :project-id (browser/current-project-id))
      ;; logout the admin
      (log-out)
      ;; first user reviews their articles
      (log-in (:email first-test-user)
              (:password first-test-user))
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount first-test-user)
                                                  label-definitions)
      (log-out)
      ;; second user reviews their articles
      (log-in (:email second-test-user)
              (:password second-test-user))
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount second-test-user)
                                                  label-definitions)
      (log-out)
      ;; third user reviews their articles
      (log-in (:email third-test-user)
              (:password third-test-user))
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount third-test-user)
                                                  label-definitions)
      ;; check that the compensation levels add up for all the reviewers
      (is (= (* (:article-amount first-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name first-test-user))))
      (is (= (* (:article-amount second-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name second-test-user))))
      (is (= (* (:article-amount third-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name third-test-user))))
      ;; create a new project
      (log-out)
      (log-in)
      ;; create the second project
      (create-project/create-project second-project-name)
      ;; import sources
      (create-project/add-articles-from-search-term first-project-search-term)
      ;; create two additional labels
      (browser/click review-articles/label-definitions-tab)
      ;; create a boolean label
      (browser/click review-articles/add-boolean-label-button)
      (review-articles/set-label-values "//div[contains(@id,'new-label-')]" review-articles/boolean-label-definition)
      (review-articles/save-label)
      ;; create a categorical label
      (browser/click review-articles/add-categorical-label-button)
      (review-articles/set-label-values "//div[contains(@id,'new-label-')]" review-articles/categorical-label-definition)
      (review-articles/save-label)
      ;; create three compensations
      (create-compensation second-project-first-compensation-amount)
      (create-compensation second-project-second-compensation-amount)
      (create-compensation second-project-third-compensation-amount)
      ;; set the first compensation amount to the default
      (select-compensation-for-user "Default New User Compensation" second-project-first-compensation-amount)
      ;; associate the other users with the second project
      (doall (map (partial project/add-project-member (browser/current-project-id))
                  [(-> (users/get-user-by-email (:email first-test-user)) :user-id)
                   (-> (users/get-user-by-email (:email second-test-user)) :user-id)
                   (-> (users/get-user-by-email (:email third-test-user)) :user-id)]))
      ;; logout the admin
      (log-out)
      ;; ;; first user reviews their articles
      (log-in (:email first-test-user)
              (:password first-test-user))
      (browser/click {:xpath (project-name-xpath-string second-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount first-test-user)
                                                  label-definitions)
      (log-out)
      ;; second user reviews their articles
      (log-in (:email second-test-user)
              (:password second-test-user))
      (browser/click {:xpath (project-name-xpath-string second-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount second-test-user)
                                                  label-definitions)
      (log-out)
      ;; third user reviews their articles
      (log-in (:email third-test-user)
              (:password third-test-user))
      (browser/click {:xpath (project-name-xpath-string second-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount third-test-user)
                                                  label-definitions)
      ;; check that the compensation levels add up for all the reviewers
      (is (= (* (:article-amount first-test-user)
                second-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name first-test-user))))
      (is (= (* (:article-amount second-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name second-test-user))))
      (is (= (* (:article-amount third-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name third-test-user))))
      ;; change the compensation level of the first test user
      (log-out)
      (log-in)
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (browser/go-project-route "/compensations")
      (select-compensation-for-user (:email first-test-user) first-project-second-compensation-amount)
      (log-out)
      (log-in (:email first-test-user)
              (:password first-test-user))
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount first-test-user)
                                                  label-definitions)
      (is (= (+ (* (:article-amount first-test-user) first-project-first-compensation-amount)
                (* (:article-amount first-test-user) first-project-second-compensation-amount))
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name first-test-user))))
      ;; change the compensation level again for the fist test user
      (log-out)
      (log-in)
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (browser/go-project-route "/compensations")
      (select-compensation-for-user (:email first-test-user) first-project-third-compensation-amount)
      (log-out)
      (log-in (:email first-test-user)
              (:password first-test-user))
      (browser/click {:xpath (project-name-xpath-string first-project-name)})
      (review-articles/randomly-review-n-articles (:article-amount first-test-user)
                                                  label-definitions)
      (is (= (+ (* (:article-amount first-test-user) first-project-first-compensation-amount)
                (* (:article-amount first-test-user) first-project-second-compensation-amount)
                (* (:article-amount first-test-user) first-project-third-compensation-amount))
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name first-test-user))))
      ;; are all the other compensation levels for the other users still consistent?
      (is (= (* (:article-amount second-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name second-test-user))))
      (is (= (* (:article-amount third-test-user)
                first-project-first-compensation-amount)
             (amount-owed-user-by-project (browser/current-project-id)
                                          (:name third-test-user))))
      (finally
        ;; log out whoever was working
        (log-out)
        ;; log in the admin user
        (log-in)
        ;; go to the first project
        (browser/click {:xpath (project-name-xpath-string first-project-name)})
        ;; delete all of the project compensations
        (doall (map (partial delete-project-compensation! (browser/current-project-id))
                    [first-project-first-compensation-amount
                     first-project-second-compensation-amount
                     first-project-third-compensation-amount]))
        ;; delete all of the second project compensations
        (browser/click {:xpath "//a[@href='/']"})
        (browser/wait-until-exists {:xpath (project-name-xpath-string second-project-name)})
        (browser/click {:xpath (project-name-xpath-string second-project-name)})
        (doall (map (partial delete-project-compensation! (browser/current-project-id))
                    [second-project-first-compensation-amount
                     second-project-second-compensation-amount
                     second-project-third-compensation-amount]))
        ;; delete all the users
        (doall (map (partial browser/delete-test-user :email)
                    [(:email first-test-user)
                     (:email second-test-user)
                     (:email third-test-user)]))
        ;; delete the second project
        (taxi/refresh)
        (browser/go-project-route "/settings")
        (browser/wait-until-exists {:xpath "//button[contains(text(),'Delete Project...')]"})
        (create-project/delete-current-project)
        ;; delete the first project
        (browser/click {:xpath (project-name-xpath-string first-project-name)})
        (create-project/delete-current-project)
        ))))
