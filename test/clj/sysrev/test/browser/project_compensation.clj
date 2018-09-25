(ns sysrev.test.browser.project-compensation
  (:require [clojure.test :refer :all]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.helpers :as sqlh :refer [select from where join delete-from]]
            [sysrev.api :as api]
            [sysrev.db.core :refer [do-query do-execute]]
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

(deftest-browser happy-path-project-compensation
  (let [project-name "SysRev Compensation Test"
        search-term "foo create"
        first-compensation-amount 100
        test-user {:name "foo"
                   :email "foo@bar.com"
                   :password "foobar"
                   :project-id (browser/current-project-id)}
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
      ;; check that they are the compensation is correct
      (let [amount-owed (get-in (api/amount-owed (browser/current-project-id)
                                             (f/unparse (f/formatter :date) (l/local-now)) ;; YYYY-MM-DD
                                             (f/unparse (f/formatter :date) (l/local-now)))
                                [:result :amount-owed])
            owed-to-user (->> amount-owed
                              (filter #(= (:name %)
                                          (:name test-user)))
                              first)]
        (is (= (* n first-compensation-amount)
               (* (:articles owed-to-user)
                  (get-in owed-to-user [:rate :amount])))))
      (finally
        (log-out)
        (log-in)
        ;; also delete all compensations
        (browser/click {:xpath (project-name-xpath-string project-name)})
        (delete-project-compensation! (browser/current-project-id) first-compensation-amount)
        (create-project/delete-current-project)
        (log-out)
        (browser/delete-test-user :email (:email test-user))))))
