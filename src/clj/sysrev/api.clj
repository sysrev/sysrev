(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require [bouncer.core :as b]
            [clojure.data.json :as json]
            [clojure.set :refer [rename-keys difference]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [keywordize-keys]]
            [me.raynes.fs :as fs]
            [ring.util.response :as response]
            [sysrev.biosource.annotations :as annotations]
            [sysrev.biosource.importance :as importance]
            [sysrev.cache :refer [db-memo]]
            [sysrev.charts :as charts]
            [sysrev.config.core :refer [env]]
            [sysrev.db.articles :as articles]
            [sysrev.db.annotations :as db-annotations]
            [sysrev.db.compensation :as compensation]
            [sysrev.db.core :as db]
            [sysrev.db.files :as files]
            [sysrev.db.funds :as funds]
            [sysrev.db.labels :as labels]
            [sysrev.db.markdown :as markdown]
            [sysrev.db.plans :as plans]
            [sysrev.db.project :as project]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]
            [sysrev.source.pmid :as src-pmid]
            [sysrev.db.users :as users]
            [sysrev.files.s3store :as s3store]
            [sysrev.source.endnote :as endnote]
            [sysrev.pubmed :as pubmed]
            [sysrev.paypal :as paypal]
            [sysrev.stripe :as stripe]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.util :refer [parse-integer]]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.biosource.predict :as predict-api])
  (:import [java.io ByteArrayInputStream]
           [java.util UUID]))

(def default-plan "Basic")
;; Error code used
(def payment-required 402)
(def forbidden 403)
(def not-found 404)
(def precondition-failed 412)
(def internal-server-error 500)
(def bad-request 400)

(def max-import-articles (:max-import-articles env))

(defmacro try-catch-response
  [body]
  `(try
     ~body
     (catch Throwable e#
       {:error {:status internal-server-error
                :message (.getMessage e#)}})))

(defn create-project-for-user!
  "Create a new project for user-id using project-name and insert a
  minimum label, returning the project in a response map"
  [project-name user-id]
  (let [{:keys [project-id] :as project}
        (project/create-project project-name)]
    (labels/add-label-overall-include project-id)
    (project/add-project-note project-id {})
    (project/add-project-member project-id user-id
                                :permissions ["member" "admin"])
    {:result
     {:success true
      :project (select-keys project [:project-id :name])}}))

(s/fdef create-project-for-user!
        :args (s/cat :project-name ::sp/name
                     :user-id ::sc/user-id)
        :ret ::sp/project)

(defn delete-project!
  "Delete a project with project-id by user-id. Checks to ensure the
  user is an admin of that project. If there are reviewed articles in
  the project, disables project instead of deleting it"
  [project-id user-id]
  (assert (or (project/member-has-permission? project-id user-id "admin")
              (in? (:permissions (users/get-user-by-id user-id)) "admin")))
  (if (project/project-has-labeled-articles? project-id)
    (do (project/disable-project! project-id)
        {:result {:success true
                  :project-id project-id}})
    (do (project/delete-project project-id)
        {:result {:success true
                  :project-id project-id}})))

(s/fdef delete-project!
  :args (s/cat :project-id int?
               :user-id int?)
  :ret map?)

(defn wrap-import-api [f]
  (let [{:keys [error]}
        (try (f)
             (catch Throwable e
               (log/warn "wrap-import-handler - unexpected error -"
                         (.getMessage e))
               (.printStackTrace e)
               {:error {:message "Import error"}}))]
    (if error
      {:error error}
      {:result {:success true}})))

(defn import-articles-from-search
  "Import PMIDS resulting from using search-term against PubMed API."
  [project-id search-term & {:keys [threads] :as options}]
  (wrap-import-api
   #(import/import-pubmed-search
     project-id {:search-term search-term} options)))

(defn import-articles-from-file
  "Import PMIDs into project-id from file. A file is a white-space/comma
  separated file of PMIDs."
  [project-id file filename & {:keys [threads] :as options}]
  (wrap-import-api
   #(import/import-pmid-file
     project-id {:file file :filename filename} options)))

(defn import-articles-from-endnote-file
  "Import articles from an Endnote XML file."
  [project-id file filename & {:keys [threads] :as options}]
  (wrap-import-api
   #(import/import-endnote-xml
     project-id {:file file :filename filename} options)))

(defn import-articles-from-pdf-zip-file
  "Import articles from the PDF files contained in a zip file. An
  article entry is created for each PDF, using filename as the article
  title."
  [project-id file filename & {:keys [threads] :as options}]
  (wrap-import-api
   #(import/import-pdf-zip
     project-id {:file file :filename filename} options)))

(defn project-sources
  "Return sources for project-id"
  [project-id]
  (if (project/project-exists? project-id)
    {:result {:success true
              :sources (source/project-sources project-id)}}
    {:error {:status not-found
             :message (str "project-id " project-id  " not found")}}))

(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret map?)

(defn delete-source!
  "Delete a source with source-id by user-id."
  [source-id]
  (cond (source/source-has-labeled-articles? source-id)
        {:error {:status forbidden
                 :message "Source contains reviewed articles"}}
        (not (source/source-exists? source-id))
        {:error {:status not-found
                 :message (str "source-id " source-id " does not exist")}}
        :else (let [project-id (source/source-id->project-id source-id)]
                (source/delete-source source-id)
                (predict-api/schedule-predict-update project-id)
                (importance/schedule-important-terms-update project-id)
                {:result {:success true}})))

(s/fdef delete-source!
        :args (s/cat :source-id int?)
        :ret map?)

(defn toggle-source
  "Toggle a source as being enabled or disabled."
  [source-id enabled?]
  (if (source/source-exists? source-id)
    (let [project-id (source/source-id->project-id source-id)]
      (source/toggle-source source-id enabled?)
      (predict-api/schedule-predict-update project-id)
      (importance/schedule-important-terms-update project-id)
      {:result {:success true}})
    {:error {:status not-found
             :message (str "source-id " source-id " does not exist")}}))

(s/fdef toggle-source
        :args (s/cat :source-id int?
                     :enabled? boolean?)
        :ret map?)

(defn register-user!
  "Register a user and add them as a stripe customer"
  [email password project-id]
  (assert (string? email))
  (let [user (users/get-user-by-email email)
        db-result
        (when-not user
          (try
            (users/create-user email password :project-id project-id)
            true
            (catch Throwable e
              e)))]
    (cond
      user
      {:result
       {:success false
        :message "Account already exists for this email address"}}
      (isa? (type db-result) Throwable)
      {:error
       {:status 500
        :message "Exception occurred while creating account"
        :exception db-result}}
      (true? db-result)
      (do
        ;; create-sysrev-stripe-customer! will handle
        ;; logging any error messages related to not
        ;; being able to create a stripe customer for the
        ;; user
        (users/create-sysrev-stripe-customer!
         (users/get-user-by-email email))
        ;; subscribe the customer to the basic plan, by default
        (stripe/subscribe-customer! (users/get-user-by-email email)
                                    default-plan)
        {:result
         {:success true}})
      :else (throw (util/should-never-happen-exception)))))

(defn stripe-payment-method
  "Using a stripe token, update the payment method for user-id"
  [user-id token]
  (let [stripe-response (stripe/update-customer-card!
                         (users/get-user-by-id user-id)
                         token)]
    (if (:error stripe-response)
      stripe-response
      {:success true})))

(defn plans
  "Get available plans"
  []
  {:result {:success true
            :plans (->> (stripe/get-plans)
                        :data
                        (filter #(not= (:name %) "ProjectSupport"))
                        (mapv #(select-keys % [:name :amount :product])))}})

(defn current-plan
  "Get the plan for user-id"
  [user-id]
  {:result {:success true
            :plan (plans/get-current-plan (users/get-user-by-id user-id))}})

(defn subscribe-to-plan
  "Subscribe user to plan-name"
  [user-id plan-name]
  (let [user (users/get-user-by-id user-id)
        stripe-response (stripe/subscribe-customer! user plan-name)]
    (if (:error stripe-response)
      (assoc stripe-response
             :error
             (merge (:error stripe-response)
                    {:status not-found}))
      stripe-response)))

(def minimum-support-level 100)

(defn support-project-monthly
  "User supports project"
  [user project-id amount]
  (let [{:keys [quantity id]} (plans/user-current-project-support user project-id)]
    (cond
      (and (not (nil? amount))
           (< amount minimum-support-level))
      {:error {:status forbidden
               :type "amount_too_low"
               :message {:minimum minimum-support-level}}}
      ;; user is not supporting this project
      (nil? quantity)
      (stripe/support-project-monthly! user project-id amount)
      ;; user is already supporting at this amount, do nothing
      (= quantity amount)
      {:error {:status forbidden
               :type "already_supported_at_amount"
               :message {:amount amount}}}
      ;; the user is supporting this project,
      ;; but not at this amount
      (not (nil? quantity))
      (do (stripe/cancel-subscription! id)
          (support-project-monthly user project-id amount))
      ;; something we hadn't planned for happened
      ;; there was another error in the request
      :else
      {:error {:message "Unexpected event in support-project"}})))

(defn support-project-once
  "User supports project-id by amount once"
  [user project-id amount]
  (cond
    (and (not (nil? amount))
         (< amount minimum-support-level))
    {:error {:status forbidden
             :type "amount_too_low"
             :message {:minimum minimum-support-level}}}
    (not (nil? amount))
    (stripe/support-project-once! user project-id amount)
    ;; something we hadn't planned for happened
    :else
    {:error {:message "Unexpected event in support-project"}}))

(defn support-project
  "User supports project-id by amount for duration of frequency"
  [user project-id amount frequency]
  (if (= frequency "monthly")
    (support-project-monthly user project-id amount)
    (support-project-once user project-id amount)))

(defonce paypal-response (atom nil))

;;https://developer.paypal.com/docs/checkout/how-to/customize-flow/#manage-funding-source-failure
;; response.error = 'INSTRUMENT_DECLINED'
(defn add-funds-paypal
  [project-id user-id response]
  ;; best way to check for now
  (let [response (keywordize-keys response)]
    (cond (:id response)
          (let [amount (-> response :transactions first (get-in [:amount :total]) read-string (* 100) (Math/round))
                transaction-id (:id response)
                created (-> response :transactions first :related_resources first :sale :create_time paypal/paypal-date->unix-epoch)]
            ;; all paypal transactions will be considered pending
            (funds/create-project-fund-pending-entry! {:project-id project-id
                                                       :user-id user-id
                                                       :amount amount
                                                       :transaction-id transaction-id
                                                       :transaction-source (:paypal-payment funds/transaction-source-descriptor)
                                                       :status "pending"
                                                       :created created})
            {:result {:success true}})
          :else {:error {:status internal-server-error
                         :message "The PayPal response has an error"}})))

(defn current-project-support-level
  "The current level of support of this user for project-id"
  [user project-id]
  {:result (select-keys (plans/user-current-project-support user project-id) [:name :project-id :quantity])})

(defn user-support-subscriptions
  "The current support subscriptions for user"
  [user]
  {:result (mapv #(select-keys % [:name :project-id :quantity])
                 (plans/user-support-subscriptions user))})

(defn cancel-project-support
  "Cancel support for project-id by user"
  [user project-id]
  (let [{:keys [quantity id]} (plans/user-current-project-support user project-id)]
    (stripe/cancel-subscription! id)
    {:result {:success true}}))

(defn finalize-stripe-user!
  "Save a stripe user in our database for payouts"
  [user-id stripe-code]
  (let [{:keys [body] :as finalize-response} (stripe/finalize-stripe-user! stripe-code)]
    (if-let [stripe-user-id (:stripe_user_id body)]
      ;; save the user information
      (try (users/create-web-user-stripe-acct stripe-user-id user-id)
           {:result {:success true}}
           (catch Throwable e
             {:error {:status internal-server-error
                      :message (.getMessage e)}}))
      ;; return an error
      {:error {:status (:status finalize-response)
               :message (:error_description body)}})))

(defn stripe-default-source
  [user-id]
  {:result {:default-source (if-let [source-default (stripe/read-default-customer-source (users/get-user-by-id user-id))]
                              source-default
                              [])}})

(defn user-has-stripe-account?
  "Does the user have a stripe account associated with the platform?"
  [user-id]
  {:connected
   (boolean (users/user-stripe-account user-id))})

(defn read-project-compensations
  "Return all project compensations for project-id"
  [project-id]
  (let [compensations (compensation/read-project-compensations project-id)]
    {:result {:success true
              :compensations compensations}}))

(defn compensation-amount-exists?
  [project-id rate]
  (->> (compensation/read-project-compensations project-id)
       (map #(get-in % [:rate]))
       (filter #(= % rate))
       empty?
       not))

(defn create-project-compensation!
  "Create a compensation for project-id with rate"
  [project-id rate]
  (if (compensation-amount-exists? project-id rate)
    {:error {:status bad-request
             :message "That compensation already exists"}}
    (do (compensation/create-project-compensation! project-id rate)
        {:result {:success true
                  :rate rate}})))

(defn project-compensation-for-users
  "Return all compensations owed for project-id using start-date and
  end-date. start-date and end-date are of the form YYYY-MM-dd
  e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of
  the day (12:00:00AM) and end-date is until the end of the
  day (11:59:59AM)."
  [project-id start-date end-date]
  (try {:result {:amount-owed (compensation/project-compensation-for-users project-id start-date end-date)}}
       (catch Throwable e
         {:error {:status internal-server-error
                  :message (.getMessage e)}})))

(defn compensation-owed
  "Return compensations owed for all users"
  [project-id]
  (try-catch-response
   {:result {:compensation-owed (->> (compensation/compensation-owed-by-project project-id))}}))

(defn project-users-current-compensation
  "Return the compensation-id for each user"
  [project-id]
  (try-catch-response
   {:result {:project-users-current-compensation (compensation/project-users-current-compensation project-id)}}))

(defn toggle-compensation-active!
  [project-id compensation-id active?]
  (try-catch-response
   (let [current-compensation (->> (compensation/read-project-compensations project-id)
                                   (filterv #(= (:id %) compensation-id))
                                   first)]
     (cond
       ;; this compensation doesn't even exist
       (nil? current-compensation)
       {:error {:status not-found}}
       ;; nothing changed, do nothing
       (= active? (:active current-compensation))
       {:result {:success true
                 :message (str "compensation-id " compensation-id " already has active set to " active?)}}
       ;; this compensation is being deactivated, so also disable all other compensations for users
       (= active? false)
       (do
         (compensation/toggle-active-project-compensation! project-id compensation-id active?)
         (compensation/end-compensation-period-for-all-users! project-id compensation-id)
         {:result {:success true}})
       (= active? true)
       (do (compensation/toggle-active-project-compensation! project-id compensation-id active?)
           {:result {:success true}})
       :else
       {:error {:status precondition-failed
                :message "An unknown error occurred"}}))))

(defn get-default-compensation
  "Get the default compensation-id for project-id"
  [project-id]
  (try-catch-response
   {:result {:success true
             :compensation-id (compensation/get-default-project-compensation project-id)}}))

(defn set-default-compensation!
  "Set the compensation-id to the default for project-id "
  [project-id compensation-id]
  (try-catch-response
   (do
     (if (= compensation-id nil)
       (compensation/delete-default-project-compensation! project-id)
       (compensation/set-default-project-compensation! project-id compensation-id))
     {:result {:success true}})))

(defn set-user-compensation!
  "Set the compensation-id for user-id in project-id"
  [project-id user-id compensation-id]
  (try-catch-response
   (let [project-compensations (->> (compensation/read-project-compensations project-id)
                                    (filter :active)
                                    (mapv :id)
                                    set)
         current-compensation-id (compensation/user-compensation project-id user-id)]
     (cond
       ;; compensation is set to none for user and they don't have a current compensation
       (and (= compensation-id "none")
            (nil? (project-compensations current-compensation-id)))
       {:result {:success true
                 :message "Compensation is already set to none for this user, no changes made"}}
       ;; compensation is set to none and they have a current compensation
       (and (= compensation-id "none")
            (not (nil? (project-compensations current-compensation-id))))
       (do (compensation/end-compensation-period-for-user! current-compensation-id user-id)
           {:result {:success true}})
       ;; there wasn't a compensation id found for the project, or it isn't active
       (nil? (project-compensations compensation-id))
       {:error {:status not-found
                :message (str "compensation-id " compensation-id " is not active or doesn't exist for project-id " project-id)}}
       ;; this is the same compensation-id as the user already has
       (= current-compensation-id compensation-id)
       {:result {:success true
                 :message "Compensation is already set to this value for the user, no changes made."}}
       ;; the user is going from having no compensation-id set to having a new one
       (nil? current-compensation-id)
       (do
         (compensation/start-compensation-period-for-user! compensation-id user-id)
         {:result {:success true}})
       ;; the user is switching compensations
       (and (project-compensations compensation-id)
            current-compensation-id)
       (do (compensation/end-compensation-period-for-user! current-compensation-id user-id)
           (compensation/start-compensation-period-for-user! compensation-id user-id)
           {:result {:success true}})
       :else
       {:error {:status precondition-failed
                :message "An unknown error occurred"}}))))

(defn calculate-project-funds
  [project-id]
  (let [available-funds (funds/project-funds project-id)
        compensation-outstanding (compensation/total-owed project-id)
        admin-fees (compensation/total-admin-fees project-id)
        current-balance (- available-funds compensation-outstanding admin-fees)
        pending-funds (->> (funds/pending-funds project-id)
                           (map :amount)
                           (apply +))]
    {:current-balance current-balance
     :admin-fees admin-fees
     :compensation-outstanding compensation-outstanding
     :available-funds available-funds
     :pending-funds pending-funds}))

(defn project-funds
  [project-id]
  {:result {:project-funds (calculate-project-funds project-id)}})


(defn check-pending-project-transactions!
  "Check the pending project transactions and update them accordingly"
  [project-id]
  (let [pending-funds (funds/pending-funds project-id)]
    (doall (map #(paypal/check-transaction! (:transaction-id %))
                pending-funds))
    {:result {:success true}}))

;; to manually add funds:
;;  (funds/create-project-fund-entry! {:project-id <project-id> :user-id <user-id> :transaction-id (str (UUID/randomUUID)) :transaction-source "Manual Entry" :amount 20000 :created (util/now-unix-seconds)})
;; in the database:
;; insert into project_fund (project_id,user_id,amount,created,transaction_id,transaction_source) values (106,1,100,(select extract(epoch from now())::int),'manual-entry','PayPal manual transfer');
(defn pay-user!
  [project-id user-id compensation admin-fee]
  (try-catch-response
   (let [available-funds (:available-funds (calculate-project-funds project-id))
         user (users/get-user-by-id user-id)
         total-amount (+ compensation admin-fee)]
     (cond
       (> total-amount available-funds)
       {:error {:status payment-required
                :message "Not enough available funds to fulfill this payment"}}
       (<= total-amount available-funds)
       (let [{:keys [status body]} (paypal/paypal-oauth-request (paypal/send-payout! user compensation))]
         (if-not (= status 201)
           {:error {:status bad-request
                    :message (get-in body [:message])}}
           (let [payout-batch-id (get-in body [:batch_header :payout_batch_id])
                 created (util/now-unix-seconds)]
             ;; deduct for funds to the user
             (funds/create-project-fund-entry! {:project-id project-id
                                                :user-id user-id
                                                :amount (- compensation)
                                                :transaction-id payout-batch-id
                                                :transaction-source (:paypal-payout funds/transaction-source-descriptor)
                                                :created created})
             ;; deduct admin fee
             (funds/create-project-fund-entry! {:project-id project-id
                                                :user-id user-id
                                                :amount (- admin-fee)
                                                :transaction-id (str (UUID/randomUUID))
                                                :transaction-source (:sysrev-admin-fee funds/transaction-source-descriptor)
                                                :created created})
             {:result "success"})))))))

(defn payments-owed
  "A list of of payments owed by all projects to user-id that are compensating user-id"
  [user-id]
  {:result {:payments-owed (compensation/payments-owed-user user-id)}})

(defn payments-paid
  "A list of all payments made to user-id by project"
  [user-id]
  {:result {:payments-paid (map #(assoc %
                                        :total-paid
                                        (* -1 (:total-paid %)))
                                (compensation/payments-paid-user user-id))}})

(defn sync-labels
  "Given a map of labels, sync them with project-id."
  [project-id labels-map]
  ;; first let's convert the labels to a set
  (let [client-labels (set (vals labels-map))
        all-labels-valid? (fn [labels]
                            (every? true? (map #(b/valid? % (labels/label-validations %)) client-labels)))]
    ;; labels must be valid
    (if (all-labels-valid? client-labels)
      ;; labels are valid
      (let [server-labels (set (vals (project/project-labels project-id true)))
            ;; new labels are given a randomly generated string id on
            ;; the client, so labels that are non-existent on the server
            ;; will have string as opposed to UUID label-ids
            new-client-labels (set (filter #(= java.lang.String
                                               (type (:label-id %)))
                                           client-labels))
            current-client-labels (set (filter #(= java.util.UUID
                                                   (type (:label-id %)))
                                               client-labels))
            modified-client-labels (difference current-client-labels server-labels)]
        ;; creation/modification of labels should be done
        ;; on labels that have been validated.
        ;;
        ;; labels are never deleted, the enabled flag is set to 'empty'
        ;; instead
        ;;
        ;; If there are issues with a label being incorrectly
        ;; modified, add a validator for that case so that
        ;; the error can easily be reported in the client
        (when-not (empty? new-client-labels)
          (doall (map (partial labels/add-label-entry project-id)
                      new-client-labels)))
        (when-not (empty? modified-client-labels)
          (doall (map #(labels/alter-label-entry project-id
                                                 (:label-id %) %)
                      modified-client-labels)))
        {:result {:valid? true
                  :labels (project/project-labels project-id true)}})
      ;; labels are invalid
      {:result {:valid? false
                :labels
                (->> client-labels
                     ;; validate each label
                     (map #(b/validate % (labels/label-validations %)))
                     ;; get the label map with attached errors
                     (map second)
                     ;; rename bouncer.core/errors -> errors
                     (map #(rename-keys % {:bouncer.core/errors :errors}))
                     ;; create a new hash map of labels which include
                     ;; errors
                     (map #(hash-map (:label-id %) %))
                     ;; finally, return a map
                     (apply merge))}})))

(defn important-terms
  "Given a project-id, return the term counts for the top n most used terms"
  [project-id & [n]]
  (let [n (or n 20)
        terms (importance/project-important-terms project-id)]
    {:result
     {:terms
      (->> terms (map-values
                  #(->> %
                        (sort-by (fn [term] (/ (:instance-count term)
                                               (:instance-score term)))  >)
                        (take n)
                        (map (fn [term] (assoc term :tfidf (/ (:instance-count term)
                                                              (:instance-score term)))))
                        (into []))))
      :loading
      (importance/project-importance-loading? project-id)}}))

(defn prediction-histogram
  "Given a project-id, return a vector of {:count <int> :score <float>}"
  [project-id]
  (db/with-project-cache
    project-id [:prediction-histogram]
    (let [all-score-vals (->> (range 0 1 0.02)
                              (mapv #(util/round-to % 0.02 2 :op :floor)))
          prediction-scores
          (->> (articles/project-prediction-scores project-id)
               (mapv #(assoc % :rounded-score
                             (-> (:val %) (util/round-to 0.02 2 :op :floor)))))
          predictions-map (zipmap (mapv :article-id prediction-scores)
                                  (mapv :rounded-score prediction-scores))
          project-article-statuses (labels/project-article-statuses project-id)
          reviewed-articles-no-conflicts
          (->> project-article-statuses
               (group-by :group-status)
               ((fn [reviewed-articles]
                  (concat
                   (:consistent reviewed-articles)
                   (:single reviewed-articles)
                   (:resolved reviewed-articles)))))
          unreviewed-articles
          (let [all-article-ids (set (mapv :article-id prediction-scores))
                reviewed-article-ids (set (mapv :article-id project-article-statuses))]
            (clojure.set/difference all-article-ids reviewed-article-ids))
          get-rounded-score-fn (fn [article-id]
                                 (get predictions-map article-id))
          reviewed-articles-scores (mapv #(assoc % :rounded-score
                                                 (get-rounded-score-fn (:article-id %)))
                                         reviewed-articles-no-conflicts)
          unreviewed-articles-scores (mapv #(hash-map :rounded-score
                                                      (get-rounded-score-fn %))
                                           unreviewed-articles)
          histogram-fn (fn [scores]
                         (let [score-counts (->> scores
                                                 (group-by :rounded-score)
                                                 (map-values count))]
                           (->> all-score-vals
                                (mapv (fn [score]
                                        {:score score
                                         :count (get score-counts score 0)}))
                                ;; trim empty sequences at start and end
                                (drop-while #(= 0 (:count %)))
                                reverse
                                (drop-while #(= 0 (:count %)))
                                reverse
                                vec)))]
      (if-not (empty? prediction-scores)
        {:result
         {:prediction-histograms
          {:reviewed-include-histogram
           (histogram-fn (filterv #(true? (:answer %))
                                  reviewed-articles-scores))
           :reviewed-exclude-histogram
           (histogram-fn (filterv #(false? (:answer %))
                                  reviewed-articles-scores))
           :unreviewed-histogram
           (histogram-fn unreviewed-articles-scores)}}}
        {:result
         {:prediction-histograms
          {:reviewed-include-histogram []
           :reviewed-exclude-histogram []
           :unreviewed-histogram []}}}))))

(def annotations-atom (atom {}))

(defn annotations-by-hash!
  "Returns the annotations by hash (.hashCode <string>). Assumes
  annotations-atom has already been set by a previous fn"
  [hash]
  (let [annotations (annotations/get-annotations
                     (get @annotations-atom hash))]
    ;; return the annotations
    annotations))

(def db-annotations-by-hash!
  (db-memo db/active-db annotations-by-hash!))

;; note: this could possibly have a thread safety issue
(defn annotations-wrapper!
  "Returns the annotations for string using a hash wrapper"
  [string]
  (let [hash (util/string->md5-hash (if (string? string)
                                      string
                                      (pr-str string)))
        _ (swap! annotations-atom assoc hash string)
        annotations (db-annotations-by-hash! hash)]
    (swap! annotations-atom dissoc hash)
    annotations))

(defn article-abstract-annotations
  "Given an article-id, return a vector of annotation maps for that
  articles abstract"
  [article-id]
  {:result {:annotations (-> article-id
                             articles/get-article
                             :abstract
                             annotations-wrapper!)}})

(defn label-count-data
  "Given a project-id, return data for the label counts chart"
  [project-id]
  {:result {:data (charts/process-label-counts project-id)}})

(defn get-s3-file
  "Given a key, return a file response"
  [key]
  (try
    (response/response (ByteArrayInputStream. (s3store/get-file key)))
    (catch Throwable e
      {:error
       {:message "Exception in label-count-data"
        :exception e}})))

(defn view-s3-pdf
  [key]
  (try
    {:headers {"Content-Type" "application/pdf"}
     :body (java.io.ByteArrayInputStream. (s3store/get-file key))}
    (catch Throwable e
      {:error
       {:message "Exception in view-s3-pdf"
        :exception e}})))

(defn save-article-pdf
  "Handle saving a file on S3 and the associated accounting with it"
  [article-id file filename]
  (let [hash (util/file->sha-1-hash file)
        s3-id (files/id-for-s3-filename-key-pair
               filename hash)
        article-s3-association (files/get-article-s3-association
                                s3-id
                                article-id)]
    (cond
      ;; there is a file and it is already associated with this article
      (not (nil? article-s3-association))
      {:result {:success true
                :key hash}}
      ;; there is a file, but it is not associated with this article
      (not (nil? s3-id))
      (try (do (files/associate-s3-with-article s3-id
                                                article-id)
               {:result {:success true
                         :key hash}})
           (catch Throwable e
             {:error {:message "error (associate article file)"
                      :exception e}}))
      ;; there is a file. but not with this filename
      (and (nil? s3-id)
           (files/s3-has-key? hash))
      (try
        (let [ ;; create the association between this file name and
              ;; the hash
              _ (files/insert-file-hash-s3-record filename hash)
              ;; get the new association's id
              s3-id (files/id-for-s3-filename-key-pair filename hash)]
          (files/associate-s3-with-article s3-id
                                           article-id)
          {:result {:success true
                    :key hash}})
        (catch Throwable e
          {:error {:message "error (associate filename)"
                   :exception e}}))
      ;; the file does not exist in our s3 store
      (and (nil? s3-id)
           (not (files/s3-has-key? hash)))
      (try
        (let [ ;; create a new file on the s3 store
              _ (s3store/save-file file)
              ;; create a new association between this file name
              ;; and the hash
              _ (files/insert-file-hash-s3-record filename hash)
              ;; get the new association's id
              s3-id (files/id-for-s3-filename-key-pair filename hash)]
          (files/associate-s3-with-article s3-id article-id)
          {:result {:success true
                    :key hash}})
        (catch Throwable e
          {:error {:message "error (store file)"
                   :exception e}}))
      :else
      {:error {:message "error (unexpected event)"}})))

(defn open-access-pdf
  [article-id key]
  (view-s3-pdf key))

(defn open-access-available?
  [article-id]
  (let [pmcid (-> article-id articles/article-pmcid)]
    (cond
      ;; the pdf exists in the store already
      (articles/pmcid-in-s3store? pmcid)
      {:result {:available? true
                :key (-> pmcid
                         (articles/pmcid->s3store-id)
                         (files/s3store-id->key))}}
      ;; there is an open access pdf filename, but we don't have it yet
      (pubmed/pdf-ftp-link pmcid)
      (try
        (let [filename (-> article-id
                           articles/article-pmcid
                           pubmed/article-pmcid-pdf-filename)
              file (java.io.File. filename)
              bytes (util/slurp-bytes file)
              save-article-result (save-article-pdf article-id file filename)
              key (get-in save-article-result [:result :key])
              s3store-id (files/id-for-s3-filename-key-pair filename key)]
          ;; delete the temporary file
          (fs/delete filename)
          ;; associate the pmcid with the s3store item
          (articles/associate-pmcid-s3store
           pmcid s3store-id)
          ;; finally, return the pdf from our own archive
          {:result {:available? true
                    :key (-> pmcid
                             (articles/pmcid->s3store-id)
                             (files/s3store-id->key))}})
        (catch Throwable e
          {:error {:message (str "open-access-available?: " (type e))
                   ;; :exception e
                   }}))

      ;; there was nothing available
      :else
      {:result {:available? false}})))

(defn article-pdfs
  "Given an article-id, return a vector of maps that correspond to the
  files associated with article-id"
  [article-id]
  (let [pmcid (-> article-id
                  articles/article-pmcid)
        pmcid-s3store-id (articles/pmcid->s3store-id pmcid)]
    {:result {:success true
              :files (->> (files/get-article-file-maps article-id)
                          (mapv #(assoc % :open-access?
                                        (if (= (:id %)
                                               pmcid-s3store-id)
                                          true
                                          false))))}}))

(defn dissociate-pdf-article
  "Remove the association between an article and PDF file."
  [article-id key filename]
  (try (do (files/dissociate-file-from-article article-id key filename)
           {:result {:success true}})
       (catch Throwable e
         {:error {:message "Exception in dissociate-pdf-article"
                  :exception e}})))

(defn process-annotation-context
  "Convert the context annotation to the one saved on the server"
  [context article-id]
  (let [text-context (:text-context context)
        article-field-match (db-annotations/text-context-article-field-match
                             text-context article-id)]
    (cond-> context
      (not= text-context article-field-match)
      (assoc :text-context {:article-id article-id
                            :field article-field-match})

      true (select-keys [:start-offset :end-offset :text-context]))))

(defn save-article-annotation
  [project-id article-id user-id selection annotation & {:keys [pdf-key context]}]
  (try
    (let [annotation-id
          (db-annotations/create-annotation!
           selection annotation
           (process-annotation-context context article-id)
           article-id)]
      (db-annotations/associate-annotation-article! annotation-id article-id)
      (db-annotations/associate-annotation-user! annotation-id user-id)
      (when pdf-key
        (let [s3store-id (files/id-for-s3-article-id-s3-key-pair article-id pdf-key)]
          (db-annotations/associate-annotation-s3store! annotation-id s3store-id)))
      {:result {:success true
                :annotation-id annotation-id}})
    (catch Throwable e
      {:error {:message "Exception in save-article-annotation"
               :exception e}})
    (finally
      (db/clear-project-cache project-id))))

(defn user-defined-annotations
  [article-id]
  (try
    (let [annotations (db-annotations/user-defined-article-annotations article-id)]
      {:result {:success true
                :annotations annotations}})
    (catch Throwable e
      {:error {:message "Exception in user-defined-annotations"
               :exception e}})))

(defn user-defined-pdf-annotations
  [article-id pdf-key]
  (try (let [s3store-id (files/id-for-s3-article-id-s3-key-pair article-id pdf-key)
             annotations (db-annotations/user-defined-article-pdf-annotations
                          article-id s3store-id)]
         {:result {:success true
                   :annotations annotations}})
       (catch Throwable e
         {:error {:message "Exception in user-defined-pdf-annotations"
                  :exception e}})))

(defn delete-annotation!
  [annotation-id]
  (let [project-id (db-annotations/annotation-id->project-id annotation-id)]
    (try
      (do
        (db-annotations/delete-annotation! annotation-id)
        {:result {:success true
                  :annotation-id annotation-id}})
      (catch Throwable e
        {:error {:message "Exception in delete-annotation!"
                 :exception e}})
      (finally
        (when project-id
          (db/clear-project-cache project-id))))))

(defn update-annotation!
  "Update the annotation for user-id. Only users can edit their own annotations"
  [annotation-id annotation semantic-class user-id]
  (try
    (if (= user-id (db-annotations/annotation-id->user-id annotation-id))
      (do
        (db-annotations/update-annotation! annotation-id annotation semantic-class)
        {:result {:success true
                  :annotation-id annotation-id
                  :annotation annotation
                  :semantic-class semantic-class}})
      {:result {:success false
                :annotation-id annotation-id
                :annotation annotation
                :semantic-class semantic-class}})
    (catch Throwable e
      {:error {:message "Exception in update-annotation!"
               :exception e}})
    (finally
      (when-let [project-id (db-annotations/annotation-id->project-id annotation-id)]
        (db/clear-project-cache project-id)))))

(defn pdf-download-url
  [article-id filename key]
  (str "/api/files/article/"
       article-id
       "/download/"
       key
       "/"
       filename))

(defn project-annotations
  [project-id]
  "Retrieve all annotations for a project"
  (let [annotations (db-annotations/project-annotations project-id)]
    (->> annotations
         (mapv #(assoc % :pmid (parse-integer (:public-id %))))
         (mapv #(if-not (nil? (and (:filename %)
                                   (:key %)))
                  (assoc % :pdf-source (pdf-download-url
                                        (:article-id %)
                                        (:filename %)
                                        (:key %)))
                  %))
         (mapv #(rename-keys % {:definition :semantic-class}))
         (mapv (fn [result]
                 (let [text-context (get-in result [:context :text-context])
                       field (:field text-context)]
                   (if (map? text-context)
                     (assoc-in result [:context :text-context]
                               (get result (keyword field)))
                     (assoc-in result [:context :text-context]
                               text-context)))))
         (mapv #(select-keys % [:selection :annotation :semantic-class
                                :pmid :article-id :pdf-source :context :user-id])))))

(defn project-annotation-status [project-id & {:keys [user-id]}]
  (let [member? (and user-id (project/member-has-permission?
                              project-id user-id "member"))]
    {:result
     {:status
      (cond-> {:project (db-annotations/project-annotation-status project-id)}
        member? (merge {:member (db-annotations/project-annotation-status
                                 project-id :user-id user-id)}))}}))

(defn change-project-permissions [project-id users-map]
  (try
    (assert project-id)
    (db/with-transaction
      (doseq [[user-id perms] (vec users-map)]
        (project/set-member-permissions project-id user-id perms))
      {:result {:success true}})
    (catch Throwable e
      {:error {:message "Exception in change-project-permissions"
               :exception e}})))

(defn read-project-description
  "Read project description of project-id"
  [project-id]
  (let [project-description (markdown/read-project-description project-id)]
    {:result {:success true
              :project-description project-description}}))

(defn set-project-description!
  "Set value for markdown description of project-id"
  [project-id markdown]
  (markdown/set-project-description! project-id markdown)
  {:result {:success true
            :project-description markdown}})

(defn public-projects []
  {:result {:projects (project/all-public-projects)}})

(defn test-response
  "Server Sanity Check"
  []
  {:test "passing"})
