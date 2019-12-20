(ns sysrev.test.browser.project-compensation
  (:require [clojure.test :refer [use-fixtures is]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-time.local :refer [local-now]]
            [clj-time.format :as tf]
            [clj-webdriver.taxi :as taxi]
            [sysrev.api :as api]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.user.core :refer [user-by-email]]
            [sysrev.project.core :as project]
            [sysrev.stacktrace :as strace]
            [sysrev.test.core :as test :refer [succeeds?]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review]
            [sysrev.test.browser.pubmed :as pm]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def payments-owed-header (xpath "//h4[contains(text(),'Payments Owed')]"))
(def project-funds-header (xpath "//h4[contains(text(),'Add Funds')]"))
(def payment-processed (xpath "//div[contains(text(),'Payment Processed')]"))

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
  (log/info "creating compensation:" amount "cents")
  (b/wait-until-loading-completes :pre-wait 30)
  (nav/go-project-route "/compensations")
  (b/wait-until-displayed ".ui.form.add-rate")
  (b/set-input-text-per-char "input#create-compensation-amount"
                             (subs (cents->string amount) 1)
                             :delay 25 :char-delay 25)
  (b/click ".ui.form.add-rate button[type=submit]")
  (b/wait-until-exists
   (xpath "//div[@id='project-rates']"
          "/descendant::span[contains(text(),'" (cents->string amount) "')]"))
  (b/wait-until-loading-completes :pre-wait 25))

(defn compensation-select [name]
  (let [name (first (str/split name #"@"))]
    (xpath "//span[contains(text(),'" name "')]"
           "/ancestor::div[contains(@class,'item')]"
           "/descendant::div[@role='listbox']")))

(defn compensation-option [name amount]
  (let [amount (if (number? amount)
                 (str (cents->string amount) " / article")
                 "None")]
    (xpath (compensation-select name)
           "/descendant::div[@role='option']"
           "/span[@class='text' and contains(text(),'" amount "')]")))

(defn select-compensation-dropdown [name amount]
  (let [name (or name "New User Default")]
    (b/click (compensation-select name) :delay 30)
    (b/click (compensation-option name amount) :delay 30)))

(defn todays-date []
  ;; YYYY-MM-DD
  (tf/unparse (tf/formatter :date) (local-now)))

(defn user-amount-owed [project-id user-name]
  (let [[user-name] (str/split user-name #"@")]
    (->> (:amount-owed (api/project-compensation-for-users
                        project-id (todays-date) (todays-date)))
         (filter #(= (:name %) user-name))
         (map #(* (:articles %)
                  (get-in % [:rate :amount])))
         (apply +))))

(defn user-amount-paid [project-name email]
  (->> (:payments-paid (api/user-payments-paid (user-by-email email :user-id)))
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
  (is (= (user-amount-owed @(:project-id project) (:email user))
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
  (is (= (user-amount-paid (:name project) (:email user))
         (string->cents (project-payments-paid (:name project))))))

(defn switch-user [{:keys [email password]} & [project]]
  (nav/log-in email password)
  (when project
    (nav/open-project (:name project))))

(defn articles-reviewed-by-user [project-id user-id]
  (q/find [:article-label :al] {:a.project-id project-id, :al.user-id user-id}
          :al.article-id, :join [:article:a :al.article-id]))

(defn articles-unreviewed-by-user [project-id user-id]
  (let [review-articles (articles-reviewed-by-user project-id user-id)]
    (q/find :article {:project-id project-id} :article-id
            :where (when (seq review-articles)
                     [:not-in :article-id review-articles]))))

(defn click-paypal-button []
  (log/info "clicking paypal button")
  (b/wait-until-exists "iframe")
  (let [button "div.paypal-button"]
    (Thread/sleep 50)
    (taxi/switch-to-default)
    (taxi/switch-to-frame
     (taxi/element (xpath (format "//iframe[@name='%s']"
                                  (first (b/current-frame-names))))))
    (b/wait-until-displayed button)
    (Thread/sleep 50)
    (b/click button :external? true)
    (Thread/sleep 100)))

;; this function is incomplete as it only handles the case of boolean labels
;; this can only create, not update labels
;;
;; FIX: remove this function
(defn randomly-set-labels [project-id user-id article-id]
  (db/with-clear-project-cache project-id
    (doseq [label (q/find :label {:project-id project-id})]
      (condp = (:value-type label)
        "boolean"   (q/create :article-label
                              {:article-label-local-id (:label-id-local label)
                               :article-id article-id
                               :label-id (:label-id label)
                               :user-id user-id
                               :answer (db/to-jsonb (nth [true false] (rand-int 2)))
                               :imported false
                               :confirm-time (db/sql-now)})))))

;; this does not check to see if n unreviewed articles really exist
(defn randomly-set-unreviewed-articles
  "Randomly set n labels for user-id in project-id"
  [project-id user-id n]
  (let [unreviewed-articles (take n (articles-unreviewed-by-user project-id user-id))]
    (doall (map (partial randomly-set-labels project-id user-id) unreviewed-articles))))

;; https://developer.paypal.com/developer/accounts/
;; test information associated with: sb-477vju643771@personal.example.com
;; visa number: 4032038001593510
;; exp: 11/24
;; everything else you can make up, except the city must match the zip code
;; still using the name "John Doe" for this as it is what the customer name associated with the account is
(defn add-paypal-funds
  "Add dollar amount of funds (e.g. $20.00) to project using paypal"
  [amount]
  (log/info "adding paypal funds" (str "($" amount ")"))
  (b/wait-until-exists project-funds-header)
  (b/wait-until-loading-completes :pre-wait 100)
  (b/set-input-text-per-char "input#paypal-amount" amount)
  (b/wait-until-loading-completes :pre-wait 100)
  (click-paypal-button)
  (log/info "switching to paypal window")
  (b/wait-until #(succeeds? (when (seq (taxi/other-windows))
                              (taxi/switch-to-window 1)
                              true))
                4000 200)
  (b/log-current-windows)
  (log/info "waiting for paypal window to load")
  (b/wait-until-displayed "a#createAccount" 15000 30)
  (Thread/sleep 200)
  (log/info "clicking to pay with Debit or Credit Card")
  (b/click "a#createAccount" :external? true)
  (b/wait-until-displayed "input#cc" 15000 30)
  (log/info "setting payment fields")
  (Thread/sleep 200)
  (letfn [(enter-text [q text]
            (taxi/focus q)
            (b/set-input-text-per-char q text :delay 20 :clear? false))]
    (enter-text "input#cc" "4032038001593510")
    (enter-text "input#expiry_value" "11/24")
    (enter-text "input#cvv" "123")
    (enter-text "input#firstName" "John")
    (enter-text "input#lastName" "Doe")
    (enter-text "input#billingLine1" "1 Infinite Loop Dr")
    (enter-text "input#billingCity" "Baltimore")
    (taxi/select-by-text "select#billingState" "Maryland")
    (Thread/sleep 30)
    (enter-text "input#billingPostalCode" "21209")
    (enter-text "input#telephone" "222-333-4444")
    (enter-text "input#email" "sb-477vju643771@personal.example.com")
    (b/click (xpath "//input[@id='guestSignup2']"
                    "/ancestor::div[contains(@class,'radioButton')]")
             :external? true)
    (Thread/sleep 100))
  (log/info "submitting paypal payment")
  (b/click "button#guestSubmit" :external? true)
  (Thread/sleep 100)
  (taxi/switch-to-window 0)
  (taxi/switch-to-default))

(defn pay-user [email]
  (let [username (first (str/split email #"@"))]
    (b/click (str "div#reviewer-amounts"
                  " div.item[data-username=" (pr-str username) "]"
                  " .ui.button.pay-user"))
    (b/click ".ui.button.confirm-pay-user")))

(deftest-browser happy-path-project-compensation
  ;; skip this from `lein test` etc, redundant with larger test
  (and (test/db-connected?) (not (test/test-profile?))) test-user
  [project-name "Sysrev Compensation Test"
   amount 100
   new-user (b/create-test-user :email "foo@bar.com" :password "foobar")
   n-articles 3
   project-id (atom nil)]
  (do (nav/log-in (:email test-user))
      ;; create a project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      ;; import sources
      (pm/import-pubmed-search-via-db "foo bar")
      ;; create a compensation level
      (create-compensation amount)
      ;; set it to default
      (select-compensation-dropdown nil amount)
      ;; create a new member, check that that their compensation level is set to the default
      (project/add-project-member @project-id (:user-id new-user))
      (randomly-set-unreviewed-articles @project-id (:user-id new-user) n-articles)
      ;; new user reviews some articles
      ;; (switch-user test-user)
      ;; (nav/open-project project-name)
      ;; (review/randomly-review-n-articles
      ;;  n-articles [review/include-label-definition])
      (is (= (* n-articles amount)
             (user-amount-owed @project-id (:email new-user)))))
  :cleanup (b/cleanup-test-user! :email (:email new-user)))

(deftest-browser multiple-project-compensations
  (test/db-connected?) test-user
  [projects (->> (list {:name "Sysrev Compensation Test 1"
                        :amounts [100 10 110]
                        :funds "20.00"}
                       {:name "Sysrev Compensation Test 2"
                        :amounts [100 20 330]
                        :funds "15.00"})
                 (map #(assoc % :project-id (atom nil))))
   [project1 project2] projects
   test-users [(merge (b/create-test-user :email "foo@bar.com" :password "foobar")
                      {:n-articles 2})
               (merge (b/create-test-user :email "baz@qux.com" :password "bazqux")
                      {:n-articles 1})
               (merge (b/create-test-user :email "corge@grault.com" :password "corgegrault")
                      {:n-articles 3})]
   [user1 user2 user3] test-users
   label-definitions [review/include-label-definition]
   review-articles (fn [user project]
                     (switch-user user project)
                     (review/randomly-review-n-articles
                      (:n-articles user) label-definitions))
   db-review-articles (fn [{:keys [user-id n-articles]} project]
                        (randomly-set-unreviewed-articles
                         @(:project-id project) user-id n-articles))
   loop-test? false]
  (dotimes [_ (if loop-test? 10 1)]
    (try
      (nav/log-in (:email test-user))
      ;; create the first project
      (nav/new-project (:name project1))
      (reset! (:project-id project1) (b/current-project-id))
      (pm/import-pubmed-search-via-db "foo bar")
      ;; create three compensations
      (doseq [amt (:amounts project1)] (create-compensation amt))
      ;; set the first compensation amount as default
      (select-compensation-dropdown nil (-> project1 :amounts (nth 0)))
      ;; add funds to the project
      (try (add-paypal-funds "20.00")
           (log/info "waiting for paypal to finish")
           (b/wait-until #(or (and (taxi/exists? payment-processed)
                                   (do (println) true))
                              (do (print ".") (flush) false))
                         30000 500)
           (catch Throwable e
             (throw (ex-info "PayPal Error" {:type :paypal} e))))
      ;; create users
      (doseq [{:keys [user-id]} test-users]
        (project/add-project-member @(:project-id project1) user-id))
      ;; review some articles from all users
      (db-review-articles user1 project1)
      (db-review-articles user2 project1)
      (db-review-articles user3 project1)
      ;; check correct values for amounts owed to users
      (doseq [user test-users]
        (is (= (* (:n-articles user) (-> project1 :amounts (nth 0)))
               (user-amount-owed @(:project-id project1) (:email user)))))
      ;; TODO: fix this to pass reliably in test-aws-dev-all
      (when (and (test/full-tests?) (not (test/remote-test?)))
        ;; create a second project
        (switch-user test-user)
        (nav/new-project (:name project2))
        (reset! (:project-id project2) (b/current-project-id))
        ;; import sources
        (pm/add-articles-from-search-term "foo create")
        ;; create three compensations
        (doseq [amt (:amounts project2)] (create-compensation amt))
        ;; set the first compensation amount as default
        (select-compensation-dropdown nil (-> project2 :amounts (nth 0)))
        ;; associate the other users with the second project
        (doseq [{:keys [user-id]} test-users]
          (project/add-project-member @(:project-id project2) user-id))
        ;; review some articles from all users
        (db-review-articles user1 project2)
        (db-review-articles user2 project2)
        (db-review-articles user3 project2)
        ;; check correct values for amounts owed to users
        (doseq [user test-users]
          (is (= (* (:n-articles user) (-> project2 :amounts (nth 0)))
                 (user-amount-owed @(:project-id project2) (:email user)))))
        (switch-user test-user project1)
        (nav/go-project-route "/compensations")
        ;; change the compensation level for user1
        (select-compensation-dropdown (:email user1) (-> project1 :amounts (nth 1)))
        (db-review-articles user1 project1)
        (is (= (* (:n-articles user1)
                  (+ (-> project1 :amounts (nth 0))
                     (-> project1 :amounts (nth 1))))
               (user-amount-owed @(:project-id project1) (:email user1))))
        (switch-user test-user project1)
        (nav/go-project-route "/compensations")
        ;; change the compensation level for user1 (again)
        (select-compensation-dropdown (:email user1) (-> project1 :amounts (nth 2)))
        (db-review-articles user1 project1)
        (is (= (* (:n-articles user1)
                  (->> project1 :amounts (take 3) (apply +)))
               (user-amount-owed @(:project-id project1) (:email user1))))
        ;; are all the other compensation levels for the other users still consistent?
        (is (= (* (:n-articles user2) (-> project1 :amounts (nth 0)))
               (user-amount-owed @(:project-id project1) (:email user2))))
        (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
               (user-amount-owed @(:project-id project1) (:email user3))))
        ;; let's change compensations for another user in this project
        (switch-user test-user project1)
        (nav/go-project-route "/compensations")
        (select-compensation-dropdown (:email user2) (-> project1 :amounts (nth 2)))
        (db-review-articles user2 project1)
        ;; are the compensations still correct for this user?
        (is (= (+ (* (:n-articles user2) (-> project1 :amounts (nth 0)))
                  (* (:n-articles user2) (-> project1 :amounts (nth 2))))
               (user-amount-owed @(:project-id project1) (:email user2))))
        ;; are all the other compensation levels for the other users still consistent?
        (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
               (user-amount-owed @(:project-id project1) (:email user3))))
        (is (= (+ (* (:n-articles user1) (-> project1 :amounts (nth 0)))
                  (* (:n-articles user1) (-> project1 :amounts (nth 1)))
                  (* (:n-articles user1) (-> project1 :amounts (nth 2))))
               (user-amount-owed @(:project-id project1) (:email user1))))
        ;; do some changes/reviews in project2 and check amounts
        (switch-user test-user project2)
        (nav/open-project (:name project2))
        ;; change rates for user1 and user2, review articles
        (nav/go-project-route "/compensations")
        (select-compensation-dropdown (:email user1) (-> project2 :amounts (nth 1)))
        (db-review-articles user1 project2)
        (nav/go-project-route "/compensations")
        (select-compensation-dropdown (:email user2) (-> project2 :amounts (nth 2)))
        (db-review-articles user2 project2)
        ;; check correct amounts for project2
        (is (= (+ (* (:n-articles user1) (-> project2 :amounts (nth 0)))
                  (* (:n-articles user1) (-> project2 :amounts (nth 1))))
               (user-amount-owed @(:project-id project2) (:email user1))))
        (is (= (+ (* (:n-articles user2) (-> project2 :amounts (nth 0)))
                  (* (:n-articles user2) (-> project2 :amounts (nth 2))))
               (user-amount-owed @(:project-id project2) (:email user2))))
        (is (= (* (:n-articles user3) (-> project2 :amounts (nth 0)))
               (user-amount-owed @(:project-id project2) (:email user3))))
        ;; switch back to first project
        (b/click {:xpath "//a[@href='/']"})
        (nav/open-project (:name project1))
        ;; check that amounts in project1 are still correct
        (is (= (+ (* (:n-articles user1) (-> project1 :amounts (nth 0)))
                  (* (:n-articles user1) (-> project1 :amounts (nth 1)))
                  (* (:n-articles user1) (-> project1 :amounts (nth 2))))
               (user-amount-owed @(:project-id project1) (:email user1))))
        (is (= (+ (* (:n-articles user2) (-> project1 :amounts (nth 0)))
                  (* (:n-articles user2) (-> project1 :amounts (nth 2))))
               (user-amount-owed @(:project-id project1) (:email user2))))
        (is (= (* (:n-articles user3) (-> project1 :amounts (nth 0)))
               (user-amount-owed @(:project-id project1) (:email user3))))
        ;; is user1 shown the correct payments owed?
        (switch-user user1)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-owed? user1 project1)
        (correct-payments-owed? user1 project2)
        ;; is user2 shown the correct payments owed?
        (switch-user user2)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-owed? user2 project1)
        (correct-payments-owed? user2 project2)
        ;; is user3 shown the correct payments owed?
        (switch-user user3)
        (b/click "#user-name-link")
        (b/click "#user-compensation")
        (b/wait-until-exists payments-owed-header)
        (correct-payments-owed? user3 project1)
        (correct-payments-owed? user3 project2)
        ;; pay some users
        (switch-user test-user project1)
        (nav/go-project-route "/compensations")
        (pay-user (:email user1))
        (pay-user (:email user2))
        ;; check that user1 & user3 are paid by project1, still owed by project2
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
          (b/create-test-user (:email test-user) :literal true))
        (if (= :paypal (:type (ex-data e)))
          (do (log/warnf "*** Exception in PayPal interaction ***\n%s"
                         (with-out-str
                           (strace/print-cause-trace-custom (ex-cause e))))
              (dotimes [_ 3] (log/warn "*****************************"))
              (log/warn                "*** Ignoring PayPal Error ***")
              (dotimes [_ 3] (log/warn "*****************************"))
              (b/take-screenshot :warn))
          (throw e)))))
  :cleanup (doseq [{:keys [email]} test-users]
             (b/cleanup-test-user! :email email)))

;; for deleting during manual test
;; (doall (map #(do (delete-project-compensations %) (project/delete-project %)) [113 114])) ; manual input of project-id
;; (doall (map (partial b/delete-test-user :email) (map :email [user1 user2 user3]))) ; user1 ... user3 should have been def'd
