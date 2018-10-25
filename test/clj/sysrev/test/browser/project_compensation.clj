(ns sysrev.test.browser.project-compensation
  (:require [clojure.test :refer :all]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.api :as api]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-jsonb clear-project-cache]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review]
            [sysrev.test.browser.pubmed :as pm]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

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
  (let [create-new-compensation {:xpath "//h4[contains(text(),'Create New Compensation')]"}
        amount-input {:xpath "//input[@id='create-compensation-amount' and @type='text']"}
        amount-create {:xpath "//button[contains(text(),'Create')]"}]
    (log/info "creating compensation:" amount "cents")
    (nav/go-project-route "/compensations")
    (b/wait-until-exists create-new-compensation)
    (b/set-input-text-per-char amount-input (cents->string amount))
    (b/click amount-create)
    (b/wait-until-exists
     (xpath "//div[contains(text(),'$" (cents->string amount) " per Article')]"))))

(defn compensation-select [user]
  (xpath "//div[contains(text(),'" user "')]"
         "/ancestor::div[contains(@class,'item')]"
         "/descendant::div[@role='listbox']"))

(defn compensation-option [user amount]
  (let [amount (if (number? amount)
                 (str "$" (cents->string amount) " / article")
                 "No Compensation")]
    (xpath (compensation-select user)
           "/descendant::div[@role='option']"
           "/span[@class='text' and text()='" amount "']")))

(defn select-compensation-for-user
  "Amount can be 'No Compensation' or integer amount of cents"
  [user amount]
  (b/click (compensation-select user))
  (b/click (compensation-option user amount)))

(defn todays-date []
  ;; YYYY-MM-DD
  (f/unparse (f/formatter :date) (l/local-now)))

(defn user-amount-owed [project-id user-name]
  (->> (get-in (api/project-compensation-for-users project-id
                                                   (todays-date)
                                                   (todays-date))
               [:result :amount-owed])
       (filter #(= (:name %) user-name))
       (map #(* (:articles %)
                (get-in % [:rate :amount])))
       (apply +)))

(defn switch-user [{:keys [email password]} & [project]]
  (nav/log-in email password)
  (when project
    (nav/open-project (:name project))))

(defn articles-reviewed-by-user
  [project-id user-id]
  (-> (select :al.article_id)
      (from [:article_label :al])
      (join [:article :a] [:= :a.article_id :al.article_id])
      (where [:and [:= :a.project-id project-id] [:= :al.user_id user-id]])
      do-query))

(defn articles-unreviewed-by-user
  [project-id user-id]
  (let [review-articles (->> (articles-reviewed-by-user project-id user-id)
                             (map :article-id))]
    (-> (select :article_id)
        (from :article)
        (where [:and
                (when-not (empty? review-articles)
                  [:not-in :article_id review-articles])
                [:= :project_id project-id]])
        do-query
        (->> (map :article-id)))))

;; this function is incomplete as it only handles the case of boolean labels
;; this can only create, not update labels
(defn randomly-set-labels
  [project-id user-id article-id]
  (try
    (with-transaction
      (let [project-labels (-> (select :label_id :value_type :definition :label_id_local)
                               (from :label)
                               (where [:= :project_id project-id])
                               do-query)]
        (doall (map (fn [label]
                      (condp = (:value-type label)
                        "boolean"
                        ;;
                        (-> (insert-into :article_label)
                            (values [{:article_label_local_id (:label-id-local label)
                                      :article_id article-id
                                      :label_id (:label-id label)
                                      :user_id user-id
                                      :answer (-> [true false]
                                                  (nth (rand-int 2))
                                                  to-jsonb)
                                      :imported false}])
                            do-execute)))
                    project-labels))))
    (finally
      (clear-project-cache project-id))))

;; this does not check to see if n unreviewed articles really exist
(defn randomly-set-n-unreviewed-articles
  [project-id user-id n]
  (let [unreviewed-articles (take n (articles-unreviewed-by-user project-id user-id))]
    (doall (map (partial randomly-set-labels project-id user-id) unreviewed-articles))))

(defn get-user-id
  "Given an email address return the user-id"
  [email]
  (-> email (users/get-user-by-email) :user-id))

(let [project-name "Sysrev Compensation Test"
      search-term "foo create"
      amount 100
      test-user {:name "foo"
                 :email "foo@bar.com"
                 :password "foobar"}
      n-articles 3
      project-id (atom nil)]
  (deftest-browser happy-path-project-compensation
    ;; skip this from `lein test` etc, redundant with larger test
    (when (and (test/db-connected?) (not (test/test-profile?)))
      ;; create a project
      (nav/log-in)
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      ;; import sources
      (pm/add-articles-from-search-term search-term)
      ;; create a compensation level
      (create-compensation amount)
      ;; set it to default
      (select-compensation-for-user "Default New User Compensation" 100)
      ;; create a new user, check that that their compensation level is set to the default
      (b/create-test-user :email (:email test-user)
                          :password (:password test-user)
                          :project-id @project-id)
      (randomly-set-n-unreviewed-articles
       @project-id (get-user-id (:email test-user)) n-articles)
      ;; new user reviews some articles
      ;; (switch-user test-user)
      ;; (nav/open-project project-name)
      ;; (review/randomly-review-n-articles
      ;;  n-articles [(merge review/include-label-definition
      ;;                     {:all-values [true false]})])
      (is (= (* n-articles amount)
             (user-amount-owed @project-id (:name test-user)))))
    :cleanup (when (and (test/db-connected?) (not (test/test-profile?)))
               (delete-project-compensations @project-id)
               (project/delete-project @project-id)
               (b/delete-test-user :email (:email test-user)))))

(let [projects
      (->> [{:name "Sysrev Compensation Test 1"
             :amounts [100 10 110]
             :search "foo create"}
            {:name "Sysrev Compensation Test 2"
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
      [user1 user2 user3] test-users]
  (deftest-browser multiple-project-compensations
    (when (test/db-connected?)
      (let [label-definitions
            [(merge review/include-label-definition
                    {:all-values [true false]})
             #_ (merge review/categorical-label-definition
                       {:all-values
                        (get-in review/categorical-label-definition
                                [:definition :all-values])})
             #_ (merge review/boolean-label-definition
                       {:all-values [true false]})]
            review-articles
            (fn [user project]
              (switch-user user project)
              (review/randomly-review-n-articles
               (:n-articles user) label-definitions))
            db-review-articles (fn [user project]
                                 (randomly-set-n-unreviewed-articles
                                  @(:project-id project)
                                  (get-user-id (:email user))
                                  (:n-articles user)))
            create-labels
            (fn [project-id]
              (nav/go-project-route "/labels/edit" project-id)
              ;; create a boolean label
              #_ (let [label review/boolean-label-definition]
                   (test/add-test-label
                    project-id
                    (merge
                     (select-keys label [:value-type :short-label :question :required])
                     {:inclusion-value (-> label :definition :inclusion-values first)})))
              ;; create a categorical label
              #_ (do (b/click review/add-categorical-label-button)
                     (review/set-label-values
                      "//div[contains(@id,'new-label-')]" review/categorical-label-definition)
                     (review/save-label))
              (nav/go-project-route "" project-id))]
        ;; login
        (nav/log-in)
        ;; create the first project
        (nav/new-project (:name project1))
        (reset! (:project-id project1) (b/current-project-id))
        (pm/add-articles-from-search-term (:search project1))
        #_ (create-labels @(:project-id project1))
        ;; create three compensations
        (mapv create-compensation (:amounts project1))
        ;; set the first compensation amount to the default
        (select-compensation-for-user
         "Default New User Compensation" (-> project1 :amounts (nth 0)))
        ;; create users
        (doseq [{:keys [email password]} test-users]
          (b/create-test-user :email email :password password
                              :project-id @(:project-id project1)))
        (db-review-articles user1 project1)
        (db-review-articles user2 project1)
        (db-review-articles user3 project1)
        #_(review-articles user1 project1)
        #_(review-articles user2 project1)
        #_(review-articles user3 project1)
        ;; check that the compensation levels add up for all the reviewers
        (doseq [user test-users]
          (is (= (* (:n-articles user) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user)))))
        (when (test/full-tests?)
          ;; create a new project
          (switch-user nil)
          ;; create the second project
          (nav/new-project (:name project2))
          (reset! (:project-id project2) (b/current-project-id))
          ;; import sources
          (pm/add-articles-from-search-term (:search project2))
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
          (db-review-articles user1 project2)
          (db-review-articles user2 project2)
          (db-review-articles user3 project2)
          ;; (review-articles user1 project2)
          ;; (review-articles user2 project2)
          ;; (review-articles user3 project2)
          ;; check that the compensation levels add up for all the reviewers
          (doseq [user test-users]
            (is (= (* (:n-articles user) (-> project2 :amounts (nth 0)))
                   (user-amount-owed @(:project-id project2) (:name user)))))
          ;; change the compensation level of the first test user
          (switch-user nil project1)
          (nav/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user1) (-> project1 :amounts (nth 1)))
          #_(review-articles user1 project1)
          (db-review-articles user1 project1)
          (is (= (* (:n-articles user1)
                    (+ (-> project1 :amounts (nth 0))
                       (-> project1 :amounts (nth 1))))
                 (user-amount-owed @(:project-id project1) (:name user1))))
          ;; change the compensation level again for the first test user
          (switch-user nil project1)
          (nav/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user1) (-> project1 :amounts (nth 2)))
          ;;(review-articles user1 project1)
          (db-review-articles user1 project1)
          (is (= (* (:n-articles user1)
                    (->> project1 :amounts (take 3) (apply +)))
                 (user-amount-owed @(:project-id project1) (:name user1))))
          ;; are all the other compensation levels for the other users still consistent?
          (is (= (* (:n-articles user2) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user2))))
          (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project1) (:name user3))))
          ;; let's change compensations for another user in this project
          (switch-user nil project1)
          (nav/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user2) (-> project1 :amounts (nth 2)))
          ;;(review-articles user2 project1)
          (db-review-articles user2 project1)
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
          (switch-user nil project2)
          (nav/open-project (:name project2))
          (nav/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user1) (-> project2 :amounts (nth 1)))
          ;;(review-articles user1 project2)
          (db-review-articles user1 project2)
          ;; let's set the compensation for the second user, have them
          ;; review some more articles
          ;;(switch-user nil project2)
          (nav/go-project-route "/compensations")
          (select-compensation-for-user
           (:email user2) (-> project2 :amounts (nth 2)))
          ;;(review-articles user2 project2)
          (db-review-articles user2 project2)
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
          (b/click {:xpath "//a[@href='/']"})
          (nav/open-project (:name project1))
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
                 (user-amount-owed @(:project-id project1) (:name user3))))))
      :cleanup (when (test/db-connected?)
                 ;; delete projects
                 (doseq [{:keys [project-id]} projects]
                   (when @project-id
                     (delete-project-compensations @project-id)
                     (project/delete-project @project-id)))
                 ;; delete test users
                 (doseq [{:keys [email]} test-users]
                   (b/delete-test-user :email email))))))
