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
            [sysrev.db.labels :as labels]
            [sysrev.test.core :refer
             [default-fixture full-tests? test-profile? add-test-label]]
            [sysrev.test.browser.core :as browser :refer [deftest-browser]]
            [sysrev.test.browser.create-project :as create-project]
            [sysrev.test.browser.navigate :refer [log-in log-out]]
            [sysrev.test.browser.review-articles :as review]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)

;;;
;;; NOTE: Compensation entries should not be deleted like this except in testing.
;;;
(defn delete-compensation-by-id [project-id compensation-id]
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
      do-execute))
;;;
(defn delete-compensation-by-amount [project-id amount]
  (delete-compensation-by-id
   project-id
   (-> (select :*)
       (from [:compensation_project :cp])
       (join [:compensation :c]
             [:= :c.id :cp.compensation_id])
       (where [:and
               [:= :cp.project_id project-id]])
       do-query
       (->> (filterv #(= (get-in % [:rate :amount]) amount)))
       first
       :id)))
;;;
(defn delete-project-compensations [project-id]
  (mapv #(delete-compensation-by-id project-id %)
        (-> (select :compensation-id)
            (from :compensation-project)
            (where [:= :project-id project-id])
            (->> do-query (map :compensation-id)))))

(defn cents->string
  "Convert an integer amount of cents to a string dollar amount"
  [cents]
  (->> (/ cents 100) double (format "%.2f")))

(defn create-compensation
  "Create a compensation in an integer amount of cents"
  [amount]
  (let [compensations {:xpath "//"}
        create-new-compensation {:xpath "//h4[contains(text(),'Create New Compensation')]"}
        amount-input {:xpath "//input[@type='text' and @id='create-compensation-amount']"}
        amount-create {:xpath "//button[contains(text(),'Create')]"}]
    (log/info (str "Creating a compensation of " amount " cents"))
    (browser/go-project-route "/compensations")
    (browser/wait-until-exists create-new-compensation)
    (taxi/clear amount-input)
    (browser/set-input-text-per-char amount-input (cents->string amount))
    (browser/click amount-create)
    (browser/wait-until-exists
     {:xpath (str "//div[contains(text(),'$" (cents->string amount)
                  " per Article')]")})))

(defn compensation-select-xpath-string  [user]
  (str "//div[contains(text(),'" user "')]"
       "/ancestor::div[contains(@class,'item')]"
       "/descendant::div[@role='listbox']"))

(defn compensation-option [user amount]
  (let [amount (if (number? amount)
                 (str "$" (cents->string amount) " / article")
                 "No Compensation")]
    (str (compensation-select-xpath-string user)
         "/descendant::div[@role='option']"
         "/span[@class='text' and text()='" amount "']")))

(defn project-name-xpath-string [project-name]
  (str "//span[contains(@class,'project-title') and text()='" project-name "']"))

(defn select-compensation-for-user
  "Amount can be 'No Compensation' or integer amount of cents"
  [user amount]
  (browser/click {:xpath (compensation-select-xpath-string user)})
  (browser/click {:xpath (compensation-option user amount)}))

(defn todays-date []
  ;; YYYY-MM-DD
  (f/unparse (f/formatter :date) (l/local-now)))

(defn user-amount-owed [project-id user-name]
  (->> (get-in (api/amount-owed project-id
                                (todays-date)
                                (todays-date))
               [:result :amount-owed])
       (filter #(= (:name %) user-name))
       (map #(* (:articles %)
                (get-in % [:rate :amount])))
       (apply +)))

(defn open-project [project-name]
  (browser/click {:xpath (project-name-xpath-string project-name)}))

(defn switch-user [{:keys [email password]} & [project-name]]
  (log-in email password false)
  (when project-name
    (open-project project-name)))

(defn review-articles [user project label-definitions]
  (switch-user user project)
  (review/randomly-review-n-articles
   (:n-articles user) label-definitions))

(deftest-browser happy-path-project-compensation
  ;; skip this from `lein test` etc, redundant with larger test
  (when (and (not (test-profile?))
             (browser/db-connected?))
    (let [project-name "SysRev Compensation Test"
          search-term "foo create"
          amount 100
          test-user {:name "foo"
                     :email "foo@bar.com"
                     :password "foobar"}
          n-articles 3
          project-id (atom nil)]
      (try
        ;; create a project
        (log-in)
        (create-project/create-project project-name)
        (reset! project-id (browser/current-project-id))
        ;; import sources
        (create-project/add-articles-from-search-term search-term)
        ;; create a compensation level
        (create-compensation amount)
        ;; set it to default
        (select-compensation-for-user "Default New User Compensation" 100)
        ;; create a new user, check that that their compensation level is set to the default
        (browser/create-test-user :email (:email test-user)
                                  :password (:password test-user)
                                  :project-id @project-id)
        ;; new user reviews some articles
        (switch-user (:name test-user))
        (open-project {:name project-name})
        (review/randomly-review-n-articles
         n-articles [(merge review/include-label-definition
                            {:all-values [true false]})])
        (is (= (* n-articles amount)
               (user-amount-owed @project-id (:name test-user))))
        (finally
          (delete-project-compensations @project-id)
          (project/delete-project @project-id)
          (browser/delete-test-user :email (:email test-user)))))))

(deftest-browser multiple-project-compensations
  (when (browser/db-connected?)
    (let [projects
          (->> [{:name "SysRev Compensation Test 1"
                 :amounts [100 10 110]
                 :search "foo create"}
                {:name "SysRev Compensation Test 2"
                 :amounts [100 20 330]
                 :search "foo create"}]
               (mapv #(assoc % :project-id (atom nil))))
          [project1 project2] projects
          test-users [{:name "foo"
                       :email "foo@bar.com"
                       :password "foobar"
                       :n-articles 2}
                      {:name "baz"
                       :email "baz@qux.com"
                       :password "bazqux"
                       :n-articles 1}
                      {:name "corge"
                       :email "corge@grault.com"
                       :password "corgegrault"
                       :n-articles 3}]
          [user1 user2 user3] test-users
          label-definitions
          [(merge review/include-label-definition
                  {:all-values [true false]})
           #_ (merge review/categorical-label-definition
                     {:all-values
                      (get-in review/categorical-label-definition
                              [:definition :all-values])})
           (merge review/boolean-label-definition
                  {:all-values [true false]})]
          create-labels
          (fn [project-id]
            (browser/go-project-route "/labels/edit" project-id)
            ;; create a boolean label
            (let [label review/boolean-label-definition]
              (add-test-label
               project-id
               (merge
                (select-keys label [:value-type :short-label :question :required])
                {:inclusion-value (-> label :definition :inclusion-values first)})))
            ;; create a categorical label
            #_ (do (browser/click review/add-categorical-label-button)
                   (review/set-label-values
                    "//div[contains(@id,'new-label-')]" review/categorical-label-definition)
                   (review/save-label))
            (browser/go-project-route "" project-id))]
      (try
        ;; login
        (log-in)
        ;; create the first project
        (create-project/create-project (:name project1))
        (reset! (:project-id project1) (browser/current-project-id))
        (create-project/add-articles-from-search-term (:search project1))
        (create-labels @(:project-id project1))
        ;; create three compensations
        (mapv create-compensation (:amounts project1))
        ;; set the first compensation amount to the default
        (select-compensation-for-user
         "Default New User Compensation" (-> project1 :amounts (nth 0)))
        ;; create users
        (doseq [{:keys [email password]} test-users]
          (browser/create-test-user :email email :password password
                                    :project-id @(:project-id project1)))
        (review-articles user1 (:name project1) label-definitions)
        (review-articles user2 (:name project1) label-definitions)
        (review-articles user3 (:name project1) label-definitions)
        ;; check that the compensation levels add up for all the reviewers
        (doseq [user test-users]
          (is (= (* (:n-articles user) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user)))))
        (when (full-tests?)
          ;; create a new project
          (switch-user nil)
          ;; create the second project
          (create-project/create-project (:name project2))
          (reset! (:project-id project2) (browser/current-project-id))
          ;; import sources
          (create-project/add-articles-from-search-term (:search project2))
          (create-labels @(:project-id project2))
          ;; create three compensations
          (mapv create-compensation (:amounts project2))
          ;; set the first compensation amount to the default
          (select-compensation-for-user
           "Default New User Compensation" (-> project2 :amounts (nth 0)))
          ;; associate the other users with the second project
          (doseq [{:keys [email]} test-users]
            (let [{:keys [user-id]} (users/get-user-by-email email)]
              (project/add-project-member @(:project-id project2) user-id)))
          (review-articles user1 (:name project2) label-definitions)
          (review-articles user2 (:name project2) label-definitions)
          (review-articles user3 (:name project2) label-definitions)
          ;; check that the compensation levels add up for all the reviewers
          (doseq [user test-users]
            (is (= (* (:n-articles user) (-> project2 :amounts (nth 0)))
                   (user-amount-owed @(:project-id project2) (:name user)))))
          ;; change the compensation level of the first test user
          (switch-user nil (:name project1))
          (browser/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user1) (-> project1 :amounts (nth 1)))
          (review-articles user1 (:name project1) label-definitions)
          (is (= (* (:n-articles user1)
                    (+ (-> project1 :amounts (nth 0))
                       (-> project1 :amounts (nth 1))))
                 (user-amount-owed @(:project-id project1) (:name user1))))
          ;; change the compensation level again for the first test user
          (switch-user nil (:name project1))
          (browser/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user1) (-> project1 :amounts (nth 2)))
          (review-articles user1 (:name project1) label-definitions)
          (is (= (* (:n-articles user1)
                    (->> project1 :amounts (take 3) (apply +)))
                 (user-amount-owed @(:project-id project1) (:name user1))))
          ;; are all the other compensation levels for the other users still consistent?
          (is (= (* (:n-articles user2) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user2))))
          (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user3))))
          ;; let's change compensations for another user in this project
          (switch-user nil (:name project1))
          (browser/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user2) (-> project1 :amounts (nth 2)))
          (review-articles user2 (:name project1) label-definitions)
          ;; are the compensations still correct for this user?
          (is (= (+ (* (:n-articles user2) (-> project1 :amounts (nth 0)))
                    (* (:n-articles user2) (-> project1 :amounts (nth 2))))
                 (user-amount-owed @(:project-id project1) (:name user2))))
          ;; are all the other compensation levels for the other users still consistent?
          (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user3))))
          (is (= (+ (* (:n-articles user1) (-> project1 :amounts (nth 0)))
                    (* (:n-articles user1) (-> project1 :amounts (nth 1)))
                    (* (:n-articles user1) (-> project1 :amounts (nth 2))))
                 (user-amount-owed @(:project-id project1) (:name user1))))
          ;; let's try changing comp rate in another project,
          ;; make sure all other compensations are correct
          (switch-user nil (:name project2))
          (browser/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user1) (-> project2 :amounts (nth 1)))
          (review-articles user1 (:name project2) label-definitions)
          ;; let's set the compensation for the second user, have them
          ;; review some more articles
          (switch-user nil (:name project2))
          (browser/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user2) (-> project2 :amounts (nth 2)))
          (review-articles user2 (:name project2) label-definitions)
          ;; does everything add up for the second project?
          (is (= (+ (* (:n-articles user1) (-> project2 :amounts (nth 0)))
                    (* (:n-articles user1) (-> project2 :amounts (nth 1))))
                 (user-amount-owed @(:project-id project2) (:name user1))))
          (is (= (+ (* (:n-articles user2) (-> project2 :amounts (nth 0)))
                    (* (:n-articles user2) (-> project2 :amounts (nth 2))))
                 (user-amount-owed @(:project-id project2) (:name user2))))
          (is (= (* (:n-articles user3) (-> project2 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project2) (:name user3))))
          ;; switch projects back to first project
          (browser/click {:xpath "//a[@href='/']"})
          (open-project (:name project1))
          ;; and the amount owed to user to the other project did not
          ;; change
          (is (= (+ (* (:n-articles user1) (-> project1 :amounts (nth 0)))
                    (* (:n-articles user1) (-> project1 :amounts (nth 1)))
                    (* (:n-articles user1) (-> project1 :amounts (nth 2))))
                 (user-amount-owed @(:project-id project1) (:name user1))))
          (is (= (+ (* (:n-articles user2) (-> project1 :amounts (nth 0)))
                    (* (:n-articles user2) (-> project1 :amounts (nth 2))))
                 (user-amount-owed @(:project-id project1) (:name user2))))
          (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user3)))))
        (finally
          ;; delete projects
          (doseq [{:keys [project-id]} projects]
            (when @project-id
              (delete-project-compensations @project-id)
              (project/delete-project @project-id)))
          ;; delete test users
          (doseq [{:keys [email]} test-users]
            (browser/delete-test-user :email email)))))))
