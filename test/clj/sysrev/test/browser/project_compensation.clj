(ns sysrev.test.browser.project-compensation
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-webdriver.taxi :as taxi]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.api :as api]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-jsonb clear-project-cache sql-now]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.label.core :as labels]
            [sysrev.test.core :as test :refer [succeeds?]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review]
            [sysrev.test.browser.semantic :as s]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.stacktrace :as stack])
  (:import clojure.lang.ExceptionInfo))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def payments-owed-header (xpath "//h4[contains(text(),'Payments Owed')]"))
(def project-funds-header (xpath "//h4[contains(text(),'Add Funds')]"))
(def add-funds-input (xpath "//input[@id='create-user-defined-support-level']"))
;; PayPal
;;(def paypal-checkout-window {:title "PayPal Checkout"})
(def cardnumber-input (xpath "//input[@id='cc']"))
;; https://developer.paypal.com/developer/accounts/
;; test information associated with:  test@insilica.co
;; visa number: 4032033154588268
;; exp: 03/24
;; everything else you can make up, except the city must match the zip code
(def visa-cardnumber "4032033154588268")
(def card-exp-input (xpath "//input[@id='expiry_value']"))
(def card-exp "03/24")
(def cvv-input (xpath "//input[@id='cvv']"))
(def first-name-input (xpath "//input[@id='firstName']"))
(def last-name-input (xpath "//input[@id='lastName']"))
(def street-address-input (xpath "//input[@id='billingLine1']"))
(def billing-city-input (xpath "//input[@id='billingCity']"))
(def billing-state-select (xpath "//select[@id='billingState']"))
(def billing-postal-code-input (xpath "//input[@id='billingPostalCode']"))
(def telephone-input (xpath "//input[@id='telephone']"))
(def email-input (xpath "//input[@id='email']"))
(def guest-signup-2-radio (xpath "//input[@id='guestSignup2']/ancestor::div[contains(@class,'radioButton')]"))
(def pay-now-button (xpath "//button[@id='guestSubmit']"))

(def payment-processed (xpath "//div[contains(text(),'Payment Processed')]"))

;; verification of emails
(def resend-verification-email (xpath "//button[contains(text(),'Resend Verification Email')]"))
(def email-verified-label (xpath "//div[contains(@class,'label') and contains(@class,'email-verified')]"))
(def email-unverified-label (xpath "//div[contains(@class,'label') and contains(@class,'email-unverified')]"))
(def primary-label (xpath "//div[contains(text(),'Primary') and contains(@class,'label')]"))
(def add-new-email-address (xpath "//button[contains(text(),'Add a New Email Address')]"))
(def new-email-address-input (xpath "//input[@id='new-email-address']"))
(def submit-new-email-address (xpath "//button[@id='new-email-address-submit']"))
(def make-primary-button (xpath "//button[@id='make-primary-button']"))
(def delete-email-button (xpath "//button[@id='delete-email-button']"))

;; opt-in for public reviewer
(def opt-in-toggle (xpath "//input[@id='opt-in-public-reviewer']"))

(defn cents->string
  "Convert an integer amount of cents to a string dollar amount"
  [cents]
  (->> (/ cents 100) double (format "%.2f") (str "$")))

(defn string->cents
  "Convert a string dollar amount to an integer amount of cents"
  [string]
  (-> string (subs 1) (str/replace #"," "") read-string (* 100) int))

(defn create-compensation
  "Create a compensation in an integer amount of cents"
  [amount]
  (let [create-new-compensation {:xpath "//h4[contains(text(),'Create New Compensation')]"}
        amount-input {:xpath "//input[@id='create-compensation-amount' and @type='text']"}
        amount-create {:xpath "//button[contains(text(),'Create')]"}]
    (log/info "creating compensation:" amount "cents")
    (b/wait-until-loading-completes :pre-wait 100)
    (nav/go-project-route "/compensations" :wait-ms 150)
    (b/wait-until-displayed create-new-compensation)
    (b/set-input-text-per-char amount-input (subs (cents->string amount) 1)
                               :delay 100 :char-delay 25)
    (b/click amount-create :delay 100)
    (b/wait-until-exists
     (xpath "//div[@id='project-compensations']"
            "/descendant::span[contains(text(),'" (cents->string amount) "')]"))
    (b/wait-until-loading-completes :pre-wait 100)))

(defn compensation-select [user]
  (xpath "//div[contains(text(),'" user "')]"
         "/ancestor::div[contains(@class,'item')]"
         "/descendant::div[@role='listbox']"))

(defn compensation-option [user amount]
  (let [amount (if (number? amount)
                 (str (cents->string amount) " / article")
                 "No Compensation")]
    (xpath (compensation-select user)
           "/descendant::div[@role='option']"
           "/span[@class='text' and contains(text(),'" amount "')]")))

(defn select-compensation-for-user
  "Amount can be 'No Compensation' or integer amount of cents"
  [user amount]
  (b/click (compensation-select user))
  (b/click (compensation-option user amount)))

(defn todays-date []
  ;; YYYY-MM-DD
  (f/unparse (f/formatter :date) (l/local-now)))

(defn user-amount-owed [project-id user-name]
  (->> (:amount-owed (api/project-compensation-for-users
                      project-id (todays-date) (todays-date)))
       (filter #(= (:name %) user-name))
       (map #(* (:articles %)
                (get-in % [:rate :amount])))
       (apply +)))

(defn user-amount-paid [project-name email]
  (->> (:payments-paid (api/payments-paid (:user-id (users/get-user-by-email email))))
       (filter #(= (:project-name %) project-name))
       (map :total-paid)
       (apply +)))

(defn project-payments-owed
  "Given a project name, how much does it owe the user?"
  [project-name]
  (if-let [element (-> (xpath (str "//h4[contains(text(),'Payments Owed')]"
                                   "/ancestor::div[contains(@class,'segment')]"
                                   "/descendant::div[text()='" project-name "']"
                                   "/ancestor::div[@class='row']"
                                   "/div[contains(text(),'$')]"))
                       taxi/find-element)]
    (taxi/text element)
    "$0.00"))

(defn correct-payments-owed?
  "Does the compensation tab show the correct payments owed to user by project-id?"
  [user project]
  (is (= (user-amount-owed @(:project-id project) (:name user))
         (string->cents (project-payments-owed (:name project))))))

(defn project-payments-paid
  "Given a project name, how much does it owe the user?"
  [project-name]
  (if-let [element (-> (xpath (str "//h4[contains(text(),'Payments Paid')]"
                                   "/ancestor::div[contains(@class,'segment')]"
                                   "/descendant::div[text()='" project-name "']"
                                   "/ancestor::div[@class='row']"
                                   "/div[contains(text(),'$')]"))
                       taxi/find-element)]
    (taxi/text element)
    "$0.00"))

(defn correct-payments-paid?
  "Does the compensation tab show the correct payments paid to user by project-id?"
  [user project]
  (is (= (user-amount-paid (:name project)
                           (:email user))
         (string->cents (project-payments-paid (:name project))))))

(defn switch-user [{:keys [email password]} & [project]]
  (nav/log-in email password)
  (when project
    (nav/open-project (:name project))))

(defn articles-reviewed-by-user
  [project-id user-id]
  (-> (select :al.article-id)
      (from [:article-label :al])
      (join [:article :a] [:= :a.article-id :al.article-id])
      (where [:and [:= :a.project-id project-id] [:= :al.user-id user-id]])
      do-query))

(defn articles-unreviewed-by-user
  [project-id user-id]
  (let [review-articles (->> (articles-reviewed-by-user project-id user-id)
                             (map :article-id))]
    (-> (select :article-id)
        (from :article)
        (where [:and
                (when-not (empty? review-articles)
                  [:not-in :article-id review-articles])
                [:= :project-id project-id]])
        do-query
        (->> (map :article-id)))))

(defn click-paypal-visa []
  (b/wait-until-exists "iframe" 5000 100)
  (Thread/sleep 200)
  (let [paypal-frame-name (first (b/current-frame-names))
        visa-button "div.paypal-button-card-visa"]
    (taxi/switch-to-default)
    (taxi/switch-to-frame (xpath (str "//iframe[@name='" paypal-frame-name "']")))
    (b/wait-until #(and (taxi/exists? visa-button)
                        (taxi/displayed? visa-button))
                  2000 30)
    (Thread/sleep 50)
    (taxi/click "div.paypal-button-card-visa")
    (Thread/sleep 50)))

;; this function is incomplete as it only handles the case of boolean labels
;; this can only create, not update labels
;;
;; FIX: remove this function
(defn randomly-set-labels
  [project-id user-id article-id]
  (try
    (with-transaction
      (let [project-labels (-> (select :label-id :value-type :definition :label-id-local)
                               (from :label)
                               (where [:= :project-id project-id])
                               do-query)]
        (doall (map (fn [label]
                      (condp = (:value-type label)
                        "boolean"
                        ;;
                        (-> (insert-into :article-label)
                            (values [{:article-label-local-id (:label-id-local label)
                                      :article-id article-id
                                      :label-id (:label-id label)
                                      :user-id user-id
                                      :answer (-> [true false]
                                                  (nth (rand-int 2))
                                                  to-jsonb)
                                      :imported false
                                      :confirm-time (sql-now)}])
                            do-execute)))
                    project-labels))))
    (finally
      (clear-project-cache project-id))))

;; this does not check to see if n unreviewed articles really exist
(defn randomly-set-unreviewed-articles
  "Randomly set n labels for user-id in project-id"
  [project-id user-id n]
  (let [unreviewed-articles (take n (articles-unreviewed-by-user project-id user-id))]
    (doall (map (partial randomly-set-labels project-id user-id) unreviewed-articles))))

(defn get-user-id
  "Given an email address return the user-id"
  [email]
  (-> email (users/get-user-by-email) :user-id))

(defn add-paypal-funds
  "Add dollar amount of funds (e.g. $20.00) to project using paypal"
  [amount]
  (log/info "adding paypal funds" (str "(" amount ")"))
  (b/set-input-text-per-char add-funds-input amount :delay 25 :char-delay 25)
  (Thread/sleep 200)
  (click-paypal-visa)
  (Thread/sleep 200)
  (b/log-current-windows)
  (log/info "switching to paypal window")
  (assert (b/try-wait b/wait-until
                      #(succeeds? (when (seq (taxi/other-windows))
                                    (taxi/switch-to-window 1)
                                    true))
                      4000 200)
          (do (b/log-current-windows)
              "switch to window failed"))
  (log/info "waiting for paypal window content")
  (b/wait-until #(if (taxi/exists? cardnumber-input)
                   (do (println) true)
                   (do (print ".") (flush) false))
                30000 500)
  (Thread/sleep 2500)
  (log/info "setting payment fields")
  (b/set-input-text-per-char cardnumber-input visa-cardnumber)
  (b/set-input-text-per-char card-exp-input card-exp)
  (b/set-input-text-per-char cvv-input "123")
  (b/set-input-text-per-char first-name-input "Foo")
  (b/set-input-text-per-char last-name-input "Bar")
  (b/set-input-text-per-char street-address-input "1 Infinite Loop Dr")
  (b/set-input-text-per-char billing-city-input "Baltimore")
  (taxi/select-by-text billing-state-select "Maryland")
  (b/set-input-text-per-char billing-postal-code-input "21209")
  (b/set-input-text-per-char telephone-input "222-333-4444")
  (b/set-input-text-per-char email-input "browser+test@insilica.co")
  (taxi/click guest-signup-2-radio)
  (taxi/click pay-now-button)
  (taxi/switch-to-window 0)
  (taxi/switch-to-default)
  (log/info "finished paypal interaction"))

(defn pay-user
  "Pay the user with email address"
  [email]
  (let [pay-button (xpath "//h4[contains(text(),'Compensation Owed')]/ancestor::div[contains(@class,'segment')]/descendant::div[contains(text(),'" email "')]/ancestor::div[contains(@class,'grid')]/descendant::button[contains(text(),'Pay') and not(contains(@class,'disabled'))]")
        confirm-button (xpath "//button[contains(text(),'Confirm') and not(contains(@class,'disabled'))]")]
    (b/click pay-button)
    (b/click confirm-button)))

(deftest-browser happy-path-project-compensation
  ;; skip this from `lein test` etc, redundant with larger test
  (and (test/db-connected?) (not (test/test-profile?)))
  [project-name "Sysrev Compensation Test"
   amount 100
   test-user {:name "foo", :email "foo@bar.com", :password "foobar"}
   n-articles 3
   project-id (atom nil)]
  (do (nav/log-in)
      ;; create a project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      ;; import sources
      (pm/import-pubmed-search-via-db "foo bar")
      ;; create a compensation level
      (create-compensation amount)
      ;; set it to default
      (select-compensation-for-user "Default New User Compensation" 100)
      ;; create a new user, check that that their compensation level is set to the default
      (b/create-test-user :email (:email test-user)
                          :password (:password test-user)
                          :project-id @project-id)
      (randomly-set-unreviewed-articles
       @project-id (get-user-id (:email test-user)) n-articles)
      ;; new user reviews some articles
      ;; (switch-user test-user)
      ;; (nav/open-project project-name)
      ;; (review/randomly-review-n-articles
      ;;  n-articles [review/include-label-definition])
      (is (= (* n-articles amount)
             (user-amount-owed @project-id (:name test-user)))))

  :cleanup (b/cleanup-test-user! :email (:email test-user)))

(deftest-browser multiple-project-compensations
  (test/db-connected?)
  [projects
   (->> [{:name "Sysrev Compensation Test 1"
          :amounts [100 10 110]
          :funds "$20.00"}
         {:name "Sysrev Compensation Test 2"
          :amounts [100 20 330]
          :funds "$15.00"}]
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
   [review/include-label-definition
    #_ (merge review/categorical-label-definition
              {:all-values
               (get-in review/categorical-label-definition
                       [:definition :all-values])})
    #_ (merge review/boolean-label-definition)]
   review-articles
   (fn [user project]
     (switch-user user project)
     (review/randomly-review-n-articles
      (:n-articles user) label-definitions))
   db-review-articles (fn [user project]
                        (randomly-set-unreviewed-articles
                         @(:project-id project)
                         (get-user-id (:email user))
                         (:n-articles user)))
   create-labels
   (fn [project-id]
     (nav/go-project-route "/labels/edit" :project-id project-id)
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
     (nav/go-project-route "" project-id))
   loop-test? false]
  (dotimes [i (if loop-test? 10 1)]
    (try
      #_ (b/start-webdriver true)
      (nav/log-in)
      ;; create the first project
      (nav/new-project (:name project1))
      (reset! (:project-id project1) (b/current-project-id))
      (pm/import-pubmed-search-via-db "foo bar")
      #_ (create-labels @(:project-id project1))
      ;; create three compensations
      (doseq [amt (:amounts project1)] (create-compensation amt))
      ;; set the first compensation amount to the default
      (select-compensation-for-user
       "Default New User Compensation" (-> project1 :amounts (nth 0)))
      (Thread/sleep 250)
      ;; add funds to the project
      (try (b/wait-until-exists project-funds-header)
           (add-paypal-funds "$20.00")
           (log/info "waiting for paypal to return")
           (b/wait-until #(or (and (taxi/exists? payment-processed)
                                   (do (println) true))
                              (do (print ".") (flush) false))
                         45000 500)
           (catch Throwable e
             (throw (ex-info "PayPal Error" {:type :paypal} e))))
      ;; create users
      (doseq [{:keys [email password]} test-users]
        (b/create-test-user :email email :password password
                            :project-id @(:project-id project1)))
      (db-review-articles user1 project1)
      (db-review-articles user2 project1)
      (db-review-articles user3 project1)
      #_ (review-articles user1 project1)
      #_ (review-articles user2 project1)
      #_ (review-articles user3 project1)
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
        (pm/add-articles-from-search-term "foo create")
        #_ (pm/import-pubmed-search-via-db "foo bar")
        #_ (create-labels @(:project-id project2))
        ;; create three compensations
        (doseq [amt (:amounts project2)] (create-compensation amt))
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
        #_ (review-articles user1 project2)
        #_ (review-articles user2 project2)
        #_ (review-articles user3 project2)
        ;; check that the compensation levels add up for all the reviewers
        (doseq [user test-users]
          (is (= (* (:n-articles user) (-> project2 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project2) (:name user)))))
        ;; change the compensation level of the first test user
        (switch-user nil project1)
        (nav/go-project-route "/compensations")
        (select-compensation-for-user
         (:email user1) (-> project1 :amounts (nth 1)))
        #_ (review-articles user1 project1)
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
        #_ (review-articles user1 project1)
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
        #_ (switch-user nil project2)
        (nav/go-project-route "/compensations")
        (select-compensation-for-user
         (:email user2) (-> project2 :amounts (nth 2)))
        #_ (review-articles user2 project2)
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
               (user-amount-owed @(:project-id project1) (:name user3))))
        ;; is foo shown the correct payments owed?
        (switch-user user1)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-owed? user1 project1)
        (correct-payments-owed? user1 project2)
        ;; is bar shown the correct payments owed?
        (switch-user user2)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-owed? user2 project1)
        (correct-payments-owed? user2 project2)
        ;; is corge shown the correct payments owed?
        (switch-user user3)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-owed? user3 project1)
        (correct-payments-owed? user3 project2)
        ;; pay some users
        (switch-user nil project1)
        (nav/go-project-route "/compensations")
        (pay-user (:name user1))
        (pay-user (:name user2))
        ;; check if user1 and user3 are paid by project1, but still owed by project2
        (switch-user user1)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-paid? user1 project1)
        (correct-payments-owed? user1 project2)
        (switch-user user3)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-paid? user3 project1)
        (correct-payments-owed? user3 project2)
        ;; user2 should be still owed by project1 and project2
        (switch-user user2)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-paid? user2 project1)
        (correct-payments-owed? user2 project2))
      (catch Throwable e
        (when loop-test?
          (doseq [{:keys [email]} test-users]
            (b/cleanup-test-user! :email email))
          (b/create-test-user))
        (if (= :paypal (:type (ex-data e)))
          (do (log/warnf "*** Exception in PayPal interaction ***\n%s"
                         (with-out-str
                           (stack/print-cause-trace-custom (ex-cause e))))
              (dotimes [i 3] (log/warn "*****************************"))
              (log/warn                "*** Ignoring PayPal Error ***")
              (dotimes [i 3] (log/warn "*****************************"))
              (b/take-screenshot :warn))
          (throw e)))))
  :cleanup (doseq [{:keys [email]} test-users]
             (b/cleanup-test-user! :email email)))

;; for deleting during manual test
;; (doall (map #(do (delete-project-compensations %) (project/delete-project %)) [113 114])) ; manual input of project-id
;; (doall (map (partial b/delete-test-user :email) (map :email [user1 user2 user3]))) ; user1 ... user3 should have been def'd

(defn email-address-row [email]
  (xpath "//h4[contains(text(),'" email "')]"
         "/ancestor::div[contains(@class,'row')]"))

(defn email-verified? [email]
  (b/exists? (xpath (email-address-row email) email-verified-label)))

(defn email-unverified? [email]
  (b/exists? (xpath (email-address-row email) email-unverified-label)))

(defn primary? [email]
  (b/exists? (xpath (email-address-row email) primary-label)))

(defn make-primary [email]
  (b/click (xpath (email-address-row email) make-primary-button)))

(defn delete-email-address [email]
  (b/click (xpath (email-address-row email) delete-email-button))
  (Thread/sleep 200))

(defn email-address-count []
  (count (taxi/find-elements (xpath "//h4[@class='email-entry']"))))

(defn your-projects-count []
  (dec (count (taxi/find-elements (xpath "//div[@id='your-projects']//h4")))))

(deftest-browser create-user-verify-email-and-invite
  (and (test/db-connected?)
       ;; TODO: invite correct user by name to fix for populated db
       ;; (staging.sysrev.com)
       (not (test/remote-test?)))
  [user1 {:email "foo@insilica.co" :password "foobar"}
   new-email-address "bar@insilica.co"
   user-id (-> (:email user1)
               users/get-user-by-email
               :user-id)]
  (do (alter-var-root #'sysrev.sendgrid/send-template-email
                      (constantly (fn [& _] (log/info "No email sent"))))
      (b/create-test-user)
      (nav/register-user (:email user1) (:password user1))
      ;; the user can't be listed as a public reviewer
      (b/click "#user-name-link")
      (b/click "#user-settings")
      (b/wait-until-exists opt-in-toggle)
      (is (taxi/attribute opt-in-toggle "disabled"))
      ;; verify the email address
      (let [{:keys [user-id email]} (users/get-user-by-email (:email user1))
            {:keys [verify-code]} (users/read-email-verification-code user-id email)]
        (b/init-route (str "/user/" user-id "/email/" verify-code))
        (is (email-verified? email))
        ;; add a new email address
        (b/click add-new-email-address)
        ;; check for a basic error
        (b/click submit-new-email-address)
        (is (s/check-for-error-message "New email address can not be blank!"))
        ;; add a new email address
        (taxi/clear new-email-address-input)
        (b/set-input-text-per-char new-email-address-input new-email-address)
        (b/click submit-new-email-address)
        (is (email-unverified? new-email-address))
        ;; verify new email address
        ;; FIX: b/init-route should not be needed
        (b/init-route (str "/user/" user-id "/email/"
                           (:verify-code (users/read-email-verification-code
                                          user-id new-email-address))))
        (is (email-verified? new-email-address))
        ;;make this email address primary
        (make-primary new-email-address)
        (is (primary? new-email-address))
        ;; make the original email primary again
        (make-primary (:email user1))
        (is (primary? (:email user1)))
        ;; delete the other email
        (delete-email-address new-email-address)
        ;; the email count should be 1
        (is (= 1 (email-address-count)))
        ;; opt-in as a public reviewer
        (b/click "#user-name-link")
        (b/click "#user-settings")
        (b/wait-until-exists opt-in-toggle)
        ;; due to the hacky nature of React Semantic UI toggle buttons,
        ;; you click the label, not the input
        (b/click (xpath "//label[@for='opt-in-public-reviewer']"))
        ;; go to the users page and see if we are listed
        (nav/go-route "/users")
        (is (b/exists? (xpath "//a[contains(text(),'foo')]")))
        ;; FIX: why is this b/init-route needed for the nav/log-in?
        (b/init-route "/")
        (nav/log-in)
        (nav/new-project "Invitation Test")
        ;; go to user and invite foo
        (nav/go-route "/users")
        (b/click (xpath (str "//a[contains(@href,'" user-id "')]")
                        "/ancestor::div"
                        "/descendant::div[@role='listbox']"))
        (b/click (xpath (str "//a[contains(@href,'" user-id "')]")
                        "/ancestor::div"
                        "/descendant::span[contains(text(),'Invitation Test')]"
                        "/ancestor::div[@role='option']"))
        (b/click (xpath "//button[contains(text(),'Invite')]"))
        (is (b/exists? (xpath "//div[contains(text(),'This user was invited as a paid-reviewer to Invitation Test')]")))
        ;; log in as foo and check invitation
        (nav/log-in (:email user1) (:password user1))
        ;; confirm we aren't a member of Invitation Test
        (b/wait-until-exists (xpath "//h4[contains(text(),'Create a New Project')]"))
        (is (= 0 (your-projects-count)))
        ;;(nav/go-route "/user/settings")
        (b/click "#user-name-link")
        (b/click "#user-settings")
        (b/click (xpath "//a[@href='/user/" user-id "/invitations']"))
        ;; accept the invitation
        (b/click (xpath "//button[contains(text(),'Accept')]"))
        (is (b/exists? (xpath "//div[contains(text(),'You accepted this invitation')]")))
        ;; are we now a member of at least one project?
        (nav/go-route "/")
        (is (= 1 (your-projects-count)))))
  :cleanup (b/cleanup-test-user! :email (:email user1)))
