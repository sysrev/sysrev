(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require
   [clj-time.coerce :as tc]
   [clj-time.core :as t]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [me.raynes.fs :as fs]
   [medley.core :as medley]
   [orchestra.core :refer [defn-spec]]
   [ring.mock.request :as mock]
   [ring.util.response :as response]
   [sysrev.annotations :as ann]
   [sysrev.api2 :as api2]
   [sysrev.article.core :as article]
   [sysrev.biosource.annotations :as api-ann]
   [sysrev.biosource.concordance :as concordance-api]
   [sysrev.biosource.countgroup :as biosource-contgroup]
   [sysrev.biosource.importance :as importance]
   [sysrev.biosource.predict :as predict-api]
   [sysrev.cache :refer [db-memo]]
   [sysrev.config :refer [env]]
   [sysrev.datasource.api :as ds-api]
   [sysrev.db.core :as db :refer [with-transaction]]
   [sysrev.db.queries :as q]
   [sysrev.encryption :as enc]
   [sysrev.file.article :as article-file]
   [sysrev.file.core :as file]
   [sysrev.file.s3 :as s3-file]
   [sysrev.file.user-image :as user-image]
   [sysrev.formats.pubmed :as pubmed]
   [sysrev.gengroup.core :as gengroup]
   [sysrev.graphql.handler :refer [graphql-handler sysrev-schema]]
   [sysrev.group.core :as group]
   [sysrev.label.core :as label]
   [sysrev.label.define :as ldefine]
   [sysrev.notification.interface :as notification]
   [sysrev.payment.paypal :as paypal]
   [sysrev.payment.plans :as plans]
   [sysrev.payment.stripe :as stripe]
   [sysrev.project.charts :as charts]
   [sysrev.project.clone :as clone]
   [sysrev.project.compensation :as compensation]
   [sysrev.project.core :as project]
   [sysrev.project.description :as description]
   [sysrev.project.funds :as funds]
   [sysrev.project.invitation :as invitation]
   [sysrev.project.member :as member]
   [sysrev.sendgrid :as sendgrid]
   [sysrev.shared.notifications :refer [combine-notifications]]
   [sysrev.shared.spec.project :as sp]
   [sysrev.shared.text :as shared]
   [sysrev.source.core :as source]
   [sysrev.source.import :as import]
   [sysrev.stacktrace :refer [print-cause-trace-custom]]
   [sysrev.user.interface :as user :refer [user-by-email]]
   [sysrev.user.interface.spec :as su]
   [sysrev.util
    :as
    util
    :refer
    [in? index-by parse-integer req-un sum uuid-from-string]]
   [sysrev.sysrev-api-client.interface.queries :as sacq])
  (:import
   (java.util.zip ZipEntry ZipOutputStream)))

;; HTTP error codes
(def payment-required 402)
(def forbidden 403)
(def not-found 404)
(def precondition-failed 412)
(def internal-server-error 500)
(def bad-request 400)
(def conflict 409)
;; server settings
(def minimum-support-level 100)
(def paywall-grandfather-date "2019-06-09 23:56:00")

(s/def ::success boolean?)

(s/def ::status int?)
(s/def ::message string?)
(s/def ::error (s/keys :opt-un [::status ::message]))

(defn or-error [primary-spec]
  (s/or :error (req-un ::error) :standard primary-spec))

(s/def ::project ::sp/project-partial)

(declare set-user-group!)

(defn project-owner-plan
  "Return the plan name for the project owner of project-id"
  [project-id]
  (with-transaction
    (let [{:keys [user-id group-id]} (project/get-project-owner project-id)
          owner-user-id (or user-id (and group-id (group/get-group-owner group-id)))]
      (if owner-user-id
        (let [plan (plans/user-current-plan owner-user-id)]
          (if (or (nil? (:status plan)) ;legacy plan
                  (= (:status plan) "active"))
            (:product-name plan)
            "Basic"))
        "Basic"))))

(defn- project-grandfathered? [project-id]
  (let [{:keys [date-created]} (q/find-one :project {:project-id project-id})]
    (t/before? (tc/from-sql-time date-created)
               (util/parse-time-string paywall-grandfather-date))))

(defn project-unlimited-access? [project-id]
  (with-transaction
    (or (project-grandfathered? project-id)
        (contains? #{"Premium"} (project-owner-plan project-id)))))

(defn change-project-settings [project-id changes]
  (with-transaction
    (doseq [{:keys [setting value]} changes]
      (cond (and (= setting :public-access)
                 (project-unlimited-access? project-id))
            (project/change-project-setting project-id (keyword setting) value)
            (and (= setting :public-access)
                 (= value false)
                 (= "Basic" (project-owner-plan project-id))) nil
            :else (project/change-project-setting project-id (keyword setting) value)))
    {:success true, :settings (project/project-settings project-id)}))

(defn-spec create-project-for-user! (req-un ::project)
  "Create a new project for user-id using project-name and insert a
  minimum label, returning the project in a response map"
  [web-server map? project-name string?, user-id int?, public-access boolean?]
  (let [{:keys [api-key]} (user/user-identity-info user-id)
        {:keys [id name]}
        #__ (-> (api2/ex!
                 web-server
                 (sacq/create-project "project{id name}")
                 {:input {:create {:name project-name :public public-access}}}
                 :api-token api-key)
                :body :data :createProject :project)
        project-id (parse-long id)]
    (db/clear-project-cache project-id)
    {:project {:name name :project-id project-id}}))

(defn sync-project-owners!
  "Given a project-id and org-id, sync the permissions of each member with the project"
  [project-id org-id]
  (db/with-clear-project-cache project-id
    (doseq [{:keys [permissions user-id]} (group/read-users-in-group
                                           (group/group-id->name org-id))]
      (let [new-perms (cond
                        (some #{"owner" "admin"} permissions) ["member" "admin"]
                        (some #{"member"} permissions) ["member"])
            existing-perms (member/member-roles project-id user-id)]
        (if (and (empty? existing-perms) (seq new-perms))
          (member/add-project-member project-id user-id :permissions new-perms :notify? false)
          (when (not= new-perms existing-perms)
            (member/set-member-permissions project-id user-id new-perms)))))))

(defn-spec create-project-for-org! (req-un ::project)
  "Create a new project for org-id using project-name and insert a
  minimum label, returning the project in a response map"
  [project-name string?, user-id int?, group-id int?, public-access boolean?]
  (with-transaction
    (let [{:keys [project-id] :as project} (project/create-project project-name)]
      (label/add-label-overall-include project-id)
      (project/add-project-note project-id {})
      (group/create-project-group! project-id group-id)
      (sync-project-owners! project-id group-id)
      (change-project-settings project-id [{:setting :public-access
                                            :value public-access}])
      (notification/create-notification
       {:adding-user-id user-id
        :group-id group-id
        :group-name (q/find-one :groups {:group-id group-id} :group-name)
        :project-id project-id
        :project-name project-name
        :type :group-has-new-project})
      {:project (select-keys project [:project-id :name])})))

(defn-spec delete-project! (req-un ::sp/project-id)
  "Delete a project with project-id by user-id. Checks to ensure the
  user is an admin of that project. If there are reviewed articles in
  the project, disables project instead of deleting it"
  [project-id int?, user-id int?]
  (assert (or (member/member-role? project-id user-id "admin")
              (in? (q/get-user user-id :permissions) "admin")))
  (if (project/project-has-labeled-articles? project-id)
    (project/disable-project! project-id)
    (project/delete-project project-id))
  {:project-id project-id})

(defn ^:api remove-current-owner [project-id]
  (db/with-clear-project-cache project-id
    (let [{:keys [user-id group-id]} (project/get-project-owner project-id)]
      (cond user-id   (member/set-member-permissions project-id user-id ["member" "admin"])
            group-id  (group/delete-project-group! project-id group-id)))))

(defn change-project-owner-to-user [project-id user-id]
  (db/with-clear-project-cache project-id
    (remove-current-owner project-id)
    (if (member/project-member project-id user-id)
      (member/set-member-permissions project-id user-id ["member" "admin" "owner"])
      (member/add-project-member project-id user-id :permissions ["member" "admin" "owner"]))))

(defn ^:api change-project-owner-to-group [project-id group-id]
  (db/with-clear-project-cache project-id
    (remove-current-owner project-id)
    ;; set project as owned by group
    (group/create-project-group! project-id group-id)
    ;; make the group owner an admin of the project
    (let [user-id (group/get-group-owner group-id)]
      (if (member/project-member project-id user-id)
        (member/set-member-permissions project-id user-id ["member" "admin" "owner"])
        ;; FIX: this breaks ownership logic? owned by user and group?
        (member/add-project-member project-id user-id :permissions ["member" "admin" "owner"])))))

(defn ^:api change-project-owner [project-id & {:keys [user-id group-id]}]
  (util/assert-single user-id group-id)
  (cond user-id   (change-project-owner-to-user project-id user-id)
        group-id  (change-project-owner-to-group project-id group-id)))

(defn ^:unused transfer-user-projects [owner-user-id & {:keys [user-id group-id]}]
  (util/assert-single user-id group-id)
  (with-transaction
    (let [users-projects (->> (user/user-projects owner-user-id [:permissions])
                              (filter #(contains? (set (:permissions %)) "owner"))
                              (mapv :project-id))]
      (cond user-id   (mapv #(change-project-owner % :user-id user-id) users-projects)
            group-id  (mapv #(change-project-owner % :group-id group-id) users-projects)))))

(defn wrap-import-api [f args]
  (let [{:keys [error import]}
        (try (f args)
             (catch Exception e
               (log/error "wrap-import-handler -" (.getMessage e))
               (log/error (with-out-str (print-cause-trace-custom e)))
               {:error {:message "Import error"}}))]

    (if error
      {:error error}
      {:result {:success (future? import)}})))

(defn import-articles-from-search
  "Import PMIDS resulting from using search-term against PubMed API."
  [request project-id search-term & {:keys [threads] :as options}]
  (wrap-import-api #(import/import-pubmed-search request project-id % options)
                   {:search-term search-term}))

(defn import-articles-from-file
  "Import PMIDs into project-id from file. A file is a white-space/comma
  separated file of PMIDs."
  [request project-id file filename & {:keys [threads] :as options}]
  (wrap-import-api #(import/import-pmid-file request project-id % options)
                   {:file file :filename filename}))

(defn import-articles-from-endnote-file
  "Import articles from an Endnote XML file."
  [request project-id file filename & {:keys [threads] :as options}]
  (wrap-import-api #(import/import-endnote-xml request project-id % options)
                   {:file file :filename filename}))

(defn import-articles-from-pdf-zip-file
  "Import articles from the PDF files contained in a zip file. An
  article entry is created for each PDF, using filename as the article
  title."
  [request project-id file filename & {:keys [threads] :as options}]
  (wrap-import-api #(import/import-pdf-zip request project-id % options)
                   {:file file :filename filename}))

(defn import-articles-from-json-file
  "Import articles from a JSON file."
  [request project-id file filename & {:keys [] :as options}]
  (wrap-import-api #(import/import-json request project-id % options)
                   {:file file :filename filename}))

(defn import-articles-from-pdfs [request project-id multipart-params & {:keys [threads] :as options}]
  (let [files (get multipart-params "files[]")]
    (wrap-import-api #(import/import-pdfs request project-id % options)
                     {:files (if (map? files)
                               [files]
                               files)})))

(defn import-articles-from-ris-file
  "Import articles from a RIS file."
  [request project-id file filename & {:keys [threads] :as options}]
  {:result {:success true}}
  (wrap-import-api #(import/import-ris request project-id % options)
                   {:file file :filename filename}))

(defn import-trials-from-search
  "Import trials resulting from CT.gov search"
  [request project-id query entity-ids & {:keys [threads] :as options}]
  (wrap-import-api #(import/import-ctgov-search request project-id % options)
                   {:entity-ids entity-ids
                    :query query}))

(defn import-trials-from-fda-drugs-docs
  "Import trials resulting from Drugs@FDA search"
  [request project-id query entity-ids & {:keys [threads] :as options}]
  (wrap-import-api #(import/import-fda-drugs-docs-search request project-id % options)
                   {:entity-ids entity-ids
                    :query query}))

(s/def ::sources vector?)

(defn-spec project-sources (req-un ::sources)
  [project-id int?]
  {:sources (source/project-sources project-id)})

(defn-spec delete-source! (-> (req-un ::success) or-error)
  [source-id int?]
  (cond (source/source-has-labeled-articles? source-id)
        {:error {:status forbidden :message "Source contains reviewed articles"}}
        (not (source/source-exists? source-id))
        {:error {:status not-found :message (str "source-id " source-id " does not exist")}}
        :else (let [project-id (source/source-id->project-id source-id)]
                (source/delete-source source-id)
                (predict-api/schedule-predict-update project-id)
                {:success true})))

(defn-spec toggle-source (-> (req-un ::success) or-error)
  "Toggle a source as being enabled or disabled."
  [source-id int?, enabled? boolean?]
  (if (source/source-exists? source-id)
    (let [project-id (source/source-id->project-id source-id)]
      (source/toggle-source source-id enabled?)
      (predict-api/schedule-predict-update project-id)
      {:success true})
    {:error {:status not-found
             :message (str "source-id " source-id " does not exist")}}))

(defn-spec update-source (-> (req-un ::success) or-error)
  "Toggle a source as being enabled or disabled."
  [source-id int?, check-new-results? boolean?, import-new-results? boolean?, notes string?]
  (if (source/source-exists? source-id)
    (let [project-id (source/source-id->project-id source-id)]
      (source/update-source project-id source-id check-new-results? import-new-results? notes)
      {:success true
       :message "Source updated"})
    {:error {:status not-found
             :message (str "source-id " source-id " does not exist")}}))

(defn-spec re-import-source (-> (req-un ::success) or-error)
  "Toggle a source as being enabled or disabled."
  [request map? source-id int? web-server map?]
  (if (source/source-exists? source-id)
    (let [project-id (source/source-id->project-id source-id)
          source (source/get-source source-id)]
      (source/re-import request project-id source {:web-server web-server})
      {:success true
       :message "Source updated"})
    {:error {:status not-found
             :message (str "source-id " source-id " does not exist")}}))

(defn source-sample
  "Return a sample article from source"
  [source-id]
  (if (source/source-exists? source-id)
    {:article (some-> (q/find :article-source {:source-id source-id}
                              :article-id, :limit 1)
                      first
                      article/get-article)}
    {:error {:status not-found
             :message (format "source-id %d does not exist" source-id)}}))

(defn update-source-cursors!
  "Update the meta of a source with new cursor information"
  [source-id cursors]
  (if (source/source-exists? source-id)
    {:source-id (source/alter-source-meta source-id #(assoc % :cursors cursors))}
    {:error {:status not-found
             :message (str "source-id " source-id " does not exists")}}))

(defn sysrev-base-url
  "Tries to determine and return the current root url for Sysrev web
  app. Returns default of \"https://sysrev.com\" if no condition
  matches."
  []
  (if (= :dev (:profile env))
    "http://localhost:4061"
    "https://sysrev.com"))

(defn send-verification-email [user-id email]
  (let [verify-code (user/email-verify-code user-id email)
        url (str (sysrev-base-url) "/user/" user-id "/email/" verify-code)]
    (sendgrid/send-template-email
     email "Verify Your Email"
     (format "Verify your email by clicking <a href='%s'>here</a>." url))
    {:success true}))

(defn send-welcome-email [email]
  (sendgrid/send-html-email
   email "Welcome to SysRev!"
   (str "Hi, <br>
    Thank you for joining SysRev!<br>
    <br>
    To create your first project, just log in at " (shared/make-link :sysrev.com  "sysrev.com")
        " and click the big green <b>New</b> button.<br>
    Once you create a project, you can import documents and invite friends to help you review.<br>
    Try joining the \"Welcome to SysRev\" project with this "
        (shared/make-link :welcome-invite-link "invite link")
        " and see what it is like to be a SysRev 'reviewer'.<br>
    <br>
    Learn more at the SysRev youtube channel, which has videos like the "
        (shared/make-link :getting-started-video "getting started guide")
        ", and " (shared/make-link :getting-started-topic "blog.sysrev.com")
        " which gives a few short tutorials.
    <br><br>
    <b>Managed Reviews</b><br>
    SysRev can be hired to help set up, manage, and analyze your large projects.
    Check out our mangiferin project ("(shared/make-link :mangiferin-part-one "blog post")") for an example managed review.
    To learn more about managed reviews, just reply to this message with questions, or submit a project description at "
        (shared/make-link :managed-review-landing "sysrev.com/managed-review")"<br>
    <br>
    Thank you for using SysRev, please email me at TJ@sysrev.com with any questions.<br>
    From,<br>
    TJ<br>
    Director of Managed Review - SysRev.com<br>"
        (shared/make-link :youtube "Youtube Channel") "<br>"
        (shared/make-link :twitter "Twitter @sysrev1") "<br>"
        (shared/make-link :blog "blog.sysrev.com") "<br>")))

(defn register-user!
  "Register a user and add them as a stripe customer"
  [email password & {:keys [project-id org-id google-user-id]}]
  (assert (string? email))
  (with-transaction
    (let [user (user-by-email email)
          db-result (when-not user
                      (try
                        (let [u (user/create-user email password
                                                  :google-user-id google-user-id)]
                          (when project-id
                            (member/add-project-member project-id (:user-id u)))
                          (when org-id
                            (set-user-group! (:user-id u) (group/group-id->name org-id) true))
                          u)
                        true
                        (catch Throwable e e)))]
      (cond user
            {:success false, :message "Account already exists for this email address"}
            (isa? (type db-result) Throwable)
            {:error {:status 500
                     :message "Exception occurred while creating account"
                     :exception db-result}}
            (true? db-result)
            (let [{:keys [user-id] :as new-user} (user-by-email email)]
              (user/create-user-stripe-customer! new-user)
              ;; create default stripe subscription for user
              (stripe/create-subscription-user! (user-by-email email))
              ;; add email verification entry for email
              (user/create-email-verification! user-id email :principal true)
              ;; send verification email
              (send-verification-email user-id email)
              {:success true})
            :else (throw (util/should-never-happen-exception))))))

(defn update-user-stripe-payment-method!
  "Update the payment method for user-id"
  [user-id payment-method]
  (let [{:keys [stripe-id]} (q/get-user user-id)
        stripe-response (stripe/update-customer-payment-method! stripe-id payment-method)]
    (if (:error stripe-response)
      stripe-response
      {:success true})))

(defn update-org-stripe-payment-method!
  "Using a stripe token, update the payment method for org-id"
  [org-id payment-method]
  (let [stripe-id (group/group-stripe-id org-id)
        stripe-response (stripe/update-customer-payment-method! stripe-id payment-method)]
    (if (:error stripe-response)
      stripe-response
      {:success true})))

(defn get-setup-intent
  "Get a Stripe SetupIntent object"
  []
  (stripe/get-setup-intent))

(defn user-current-plan [user-id]
  {:success true, :plan (plans/user-current-plan user-id)})

(defn group-current-plan [group-id]
  (let [owner-user-id (group/get-group-owner group-id)]
    (user-current-plan owner-user-id)))

;; manually add:
;; 1. connect to prod database
;; 2. redefine keys in sysrev.payment.stripe
;; (def stripe-secret-key "sk_live_________________________")
;; then re-eval default-req
;; 3. Check the plan id matches the one on stripe.com
;; (stripe/get-plan-id "Unlimited_User")
;; 4. subscribe the user
;; (subscribe-user-to-plan <user-id> "Unlimited_User")
(defn subscribe-user-to-plan [user-id plan]
  (with-transaction
    (let [{:keys [sub-id]} (plans/user-current-plan user-id)
          plan-id (:id plan)
          sub-item-id (stripe/get-subscription-item sub-id)
          sub-resp (stripe/update-subscription-item! {:id sub-item-id :plan-id plan-id})]
      (if (:error sub-resp)
        (update sub-resp :error #(merge % {:status not-found}))
        (do (plans/add-user-to-plan! user-id plan-id sub-id)
            (doseq [{:keys [project-id]} (user/user-owned-projects user-id)]
              (db/clear-project-cache project-id))
            (when (and (= (:nickname plan) "Basic")
                       (:dev-account-enabled? (user/user-settings user-id)))
              (user/change-user-setting user-id :dev-account-enabled? false)
              (ds-api/toggle-account-enabled! (clojure.set/rename-keys (user/user-by-id user-id)
                                                                       {:api-token :api-key}) false))
            {:stripe-body sub-resp :plan (plans/user-current-plan user-id)})))))

(defn subscribe-org-to-plan [group-id plan]
  (with-transaction
    (let [{:keys [sub-id]} (plans/group-current-plan group-id)
          plan-id (:id plan)
          sub-item-id (stripe/get-subscription-item sub-id)
          sub-resp (stripe/update-subscription-item!
                    {:id sub-item-id :plan plan-id
                     :quantity (count (group/read-users-in-group
                                       (group/group-id->name group-id)))})]
      (if (:error sub-resp)
        (update sub-resp :error #(merge % {:status not-found}))
        (do (plans/add-group-to-plan! group-id plan-id sub-id)
            (doseq [{:keys [project-id]} (group/group-projects group-id :private-projects? true)]
              (db/clear-project-cache project-id))
            {:stripe-body sub-resp :plan (plans/group-current-plan group-id)})))))


(defn ^:unused support-project-monthly [user project-id amount]
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

(defn support-project-once [user project-id amount]
  (assert amount)
  (if (< amount minimum-support-level)
    {:error {:status forbidden :type "amount_too_low"
             :message {:minimum minimum-support-level}}}
    (stripe/support-project-once! user project-id amount)))

(defn support-project
  "User supports project-id by amount, repeating by frequency"
  [user project-id amount frequency]
  (if (= frequency "monthly")
    (support-project-monthly user project-id amount)
    (support-project-once user project-id amount)))

;;https://developer.paypal.com/docs/checkout/how-to/customize-flow/#manage-funding-source-failure
;; response.error = 'INSTRUMENT_DECLINED'
(defn add-funds-paypal
  [project-id user-id order-id]
  ;; best way to check for now
  (let [response (paypal/paypal-oauth-request (paypal/get-order order-id))
        {:keys [amount status created]} (paypal/process-order response)]
    (cond
      ;; PayPal capture has occurred client side
      (and (= status "COMPLETED")
           (= (:status response) 200))
      (do
        (funds/create-project-fund-entry!
         {:project-id project-id
          :user-id user-id
          :amount amount
          :transaction-id order-id
          :transaction-source (:paypal-order funds/transaction-source-descriptor)
          :created created})
        {:success true})
      (not= status "COMPLETED")
      {:error {:status precondition-failed
               :message (str "Capture status was not COMPLETED, but rather: " status)}}
      :else {:status internal-server-error
             :message "An unknown error occurred, payment was not processed on SysRev"} )))

(defn user-project-support-level [user project-id]
  {:result (select-keys (plans/user-current-project-support user project-id)
                        [:name :project-id :quantity])})

(defn user-support-subscriptions [user]
  {:result (mapv #(select-keys % [:name :project-id :quantity])
                 (plans/user-support-subscriptions user))})

(defn ^:unused cancel-user-project-support [user project-id]
  (let [{:keys [id]} (plans/user-current-project-support user project-id)]
    (stripe/cancel-subscription! id)
    {:success true}))

(defn org-available-plans
  []
  {:success true
   :plans
   (->> (stripe/get-plans)
        :data
        (map #(select-keys % [:tiers :nickname :id :interval :amount]))
        (filter #(let [nickname (:nickname %)]
                   (when-not (nil? nickname)
                     (or (re-matches #"Unlimited_Org.*" nickname)
                         (= "Basic" nickname)))))
        (into []))})

;; Everyone is Premium (formerly team pro) now
(def user-available-plans org-available-plans)

(defn ^:unused finalize-stripe-user!
  "Save a stripe user in our database for payouts"
  [user-id stripe-code]
  (let [{:keys [body] :as response} (stripe/finalize-stripe-user! stripe-code)]
    (if-let [stripe-user-id (:stripe_user_id body)]
      (do (user/create-user-stripe stripe-user-id user-id)
          {:success true})
      {:error {:status (:status response)
               :message (:error_description body)}})))

(defn user-default-stripe-source [user-id]
  (with-transaction
    {:default-source (or (some-> (q/get-user user-id :stripe-id)
                                 (stripe/get-customer-invoice-default-payment-method))
                         [])}))

(defn org-default-stripe-source [org-id]
  (with-transaction
    {:default-source (or (some-> (group/group-stripe-id org-id)
                                 (stripe/get-customer-invoice-default-payment-method))
                         [])}))

(defn user-has-stripe-account? [user-id]
  {:connected (boolean (user/user-stripe-account user-id))})

(defn read-project-compensations
  "Return all project compensations for project-id"
  [project-id]
  {:compensations (compensation/read-project-compensations project-id)})

(defn compensation-amount-exists? [project-id rate]
  (boolean (seq (->> (compensation/read-project-compensations project-id)
                     (filter #(= rate (get-in % [:rate])))))))

(defn create-project-compensation!
  "Create a compensation for project-id with rate."
  [project-id rate]
  (if (compensation-amount-exists? project-id rate)
    {:error {:status bad-request, :message "That compensation already exists"}}
    (do (compensation/create-project-compensation! project-id rate)
        {:success true, :rate rate})))

(defn project-compensation-for-users
  "Return all compensations owed for project-id using start-date and
  end-date. start-date and end-date are of the form YYYY-MM-dd
  e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of
  the day (12:00:00AM) and end-date is until the end of the
  day (11:59:59AM)."
  [project-id start-date end-date]
  {:amount-owed (compensation/project-compensation-for-users
                 project-id start-date end-date)})

(defn compensation-owed
  "Return compensations owed for all users by project-id"
  [project-id]
  (let [public-info (->> (project/project-user-ids project-id)
                         (user/get-users-public-info)
                         (index-by :user-id))
        compensation-owed-by-project (compensation/compensation-owed-by-project project-id)]
    {:compensation-owed (map #(merge % (get public-info (:user-id %)))
                             compensation-owed-by-project)}))

(defn project-users-current-compensation
  "Return the compensation-id for each user"
  [project-id]
  {:project-users-current-compensation
   (compensation/project-users-current-compensation project-id)})

(defn toggle-compensation-enabled! [project-id compensation-id enabled]
  (let [current-compensation (->> (compensation/read-project-compensations project-id)
                                  (filterv #(= (:compensation-id %) compensation-id))
                                  first)]
    (cond (nil? current-compensation)
          {:error {:status not-found}}
          (= enabled (:enabled current-compensation))
          {:success true :message (str "compensation-id " compensation-id
                                       " already has enabled = " enabled)}
          (false? enabled)
          (do (compensation/set-project-compensation-enabled! project-id compensation-id enabled)
              (compensation/end-compensation-period-for-all-users! project-id compensation-id)
              {:success true})
          (true? enabled)
          (do (compensation/set-project-compensation-enabled! project-id compensation-id enabled)
              {:success true}))))

(defn get-default-compensation [project-id]
  {:success true, :compensation-id (compensation/get-default-project-compensation project-id)})

(defn set-default-compensation! [project-id compensation-id]
  (compensation/set-default-project-compensation! project-id compensation-id)
  {:success true})

(defn set-user-compensation!
  "Set the compensation-id for user-id in project-id."
  [project-id user-id compensation-id]
  (let [project-compensations (set (->> (compensation/read-project-compensations project-id)
                                        (filter :enabled)
                                        (map :compensation-id)))
        current-compensation-id (compensation/user-compensation project-id user-id)]
    (cond
      ;; compensation is set to none for user and they don't have a current compensation
      (and (= compensation-id "none")
           (nil? (project-compensations current-compensation-id)))
      {:success true
       :message "Compensation is already set to none for this user, no changes made"}
      ;; compensation is set to none and they have a current compensation
      (and (= compensation-id "none")
           (not (nil? (project-compensations current-compensation-id))))
      (do (compensation/end-compensation-period-for-user! current-compensation-id user-id)
          {:success true})
      ;; there wasn't a compensation id found for the project, or it isn't enabled
      (nil? (project-compensations compensation-id))
      {:error {:status not-found
               :message (str "compensation-id " compensation-id
                             " is not enabled or doesn't exist for project-id " project-id)}}
      ;; this is the same compensation-id as the user already has
      (= current-compensation-id compensation-id)
      {:success true
       :message "Compensation is already set to this value for the user, no changes made."}
      ;; the user is going from having no compensation-id set to having a new one
      (nil? current-compensation-id)
      (do (compensation/start-compensation-period-for-user! compensation-id user-id)
          {:success true})
      ;; the user is switching compensations
      (and (project-compensations compensation-id)
           current-compensation-id)
      (do (compensation/end-compensation-period-for-user! current-compensation-id user-id)
          (compensation/start-compensation-period-for-user! compensation-id user-id)
          {:success true})
      :else {:error {:status precondition-failed
                     :message "An unknown error occurred"}})))

(defn calculate-project-funds [project-id]
  (let [available-funds (funds/project-funds project-id)
        compensation-outstanding (compensation/project-total-owed project-id)
        admin-fees (compensation/project-total-admin-fees project-id)
        current-balance (- available-funds compensation-outstanding admin-fees)
        pending-funds (sum (map :amount (funds/pending-funds project-id)))]
    {:current-balance current-balance
     :admin-fees admin-fees
     :compensation-outstanding compensation-outstanding
     :available-funds available-funds
     :pending-funds pending-funds}))

(defn project-funds [project-id]
  {:project-funds (calculate-project-funds project-id)})

(defn check-pending-project-transactions!
  "Check the pending project transactions and update them accordingly"
  [project-id]
  (doseq [{:keys [transaction-id]} (funds/pending-funds project-id)]
    (paypal/check-transaction! transaction-id))
  {:success true})

;; to manually add funds:
;;  (funds/create-project-fund-entry! {:project-id <project-id> :user-id <user-id> :transaction-id (str (random-uuid)) :transaction-source "Manual Entry" :amount 20000 :created (util/to-epoch db/sql-now)})
;; in the database:
;; insert into project_fund (project_id,user_id,amount,created,transaction_id,transaction_source) values (106,1,100,(select extract(epoch from now())::int),'manual-entry','PayPal manual transfer');
(defn pay-user!
  [project-id user-id compensation admin-fee]
  (let [user (q/get-user user-id)
        total-amount (+ compensation admin-fee)
        {:keys [available-funds]} (calculate-project-funds project-id)]
    (if (> total-amount available-funds)
      {:error {:status payment-required
               :message "Not enough available funds to fulfill this payment"}}
      (let [{:keys [status body]} (paypal/paypal-oauth-request
                                   (paypal/send-payout! user compensation))]
        (if (not= status 201)
          {:error {:status bad-request :message (get-in body [:message])}}
          (let [payout-batch-id (get-in body [:batch_header :payout_batch_id])]
            ;; deduct for funds to the user
            (funds/create-project-fund-entry!
             {:project-id project-id
              :user-id user-id
              :amount (- compensation)
              :transaction-id payout-batch-id
              :transaction-source (:paypal-payout funds/transaction-source-descriptor)})
            ;; deduct admin fee
            (funds/create-project-fund-entry!
             {:project-id project-id
              :user-id user-id
              :amount (- admin-fee)
              :transaction-id (str (random-uuid))
              :transaction-source (:sysrev-admin-fee funds/transaction-source-descriptor)})
            {:result "success"}))))))

(defn user-payments-owed [user-id]
  {:payments-owed (compensation/payments-owed-to-user user-id)})

(defn user-payments-paid [user-id]
  {:payments-paid (->> (compensation/payments-paid-to-user user-id)
                       (map #(update % :total-paid (partial * -1))))})

(defn sync-labels [project-id labels-map]
  ;; first let's convert the labels to a set
  (db/with-clear-project-cache project-id
    (if (ldefine/all-labels-valid? labels-map)
      ;; labels are valid
      (label/sync-labels project-id labels-map)
      ;; labels are invalid
      {:valid? false
       :labels (ldefine/validated-labels labels-map)})))

(defn project-important-terms-text [project-id & [max-terms]]
  (importance/project-important-terms project-id (or max-terms 20)))

(defn project-concordance [project-id & {:keys [keep-resolved] :or {keep-resolved true}}]
  (concordance-api/project-concordance project-id :keep-resolved keep-resolved))

(defn project-label-count-groups [project-id]
  (biosource-contgroup/get-label-countgroup project-id))

(defn project-prediction-histogram [project-id]
  {:prediction-histograms (sysrev.biosource.predict/project-prediction-histogram project-id 40)})

(def annotations-atom (atom {}))

(defn annotations-by-hash!
  "Returns the annotations by hash (.hashCode <string>). Assumes
  annotations-atom has already been set by a previous fn"
  [hash]
  (api-ann/get-annotations (get @annotations-atom hash)))

(def db-annotations-by-hash!
  (db-memo db/*active-db* annotations-by-hash!))

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
  "Returns a vector of annotation maps for an article abstract"
  [article-id]
  {:annotations (annotations-wrapper! (:abstract (article/get-article article-id)))})

(defn label-count-chart-data [project-id]
  {:data (charts/process-label-counts project-id)})

(defn save-article-pdf [article-id file filename]
  {:success true, :key (:key (article-file/save-article-pdf
                              {:article-id article-id :filename filename :file file}))})

(defn open-access-available? [article-id]
  (let [pmcid (-> article-id article/article-pmcid)]
    (cond
      ;; the pdf exists in the store already
      (article-file/pmcid->s3-id pmcid)
      {:available? true, :key (-> pmcid article-file/pmcid->s3-id file/s3-key)}
      ;; there is an open access pdf filename, but we don't have it yet
      (pubmed/pdf-ftp-link pmcid)
      (try
        (let [^String filename (-> article-id
                                   article/article-pmcid
                                   pubmed/article-pmcid-pdf-filename)
              file (java.io.File. filename)
              save-article-result (save-article-pdf article-id file filename)
              key (:key save-article-result)
              s3-id (file/lookup-s3-id filename key)]
          ;; delete the temporary file
          (fs/delete filename)
          ;; associate the pmcid with the s3store item
          (article-file/associate-pmcid-s3store pmcid s3-id)
          ;; finally, return the pdf from our own archive
          {:available? true, :key (-> pmcid article-file/pmcid->s3-id file/s3-key)})
        (catch Throwable e
          {:error {:message (str "open-access-available?: " (type e))}}))

      ;; there was nothing available
      :else {:available? false})))

(defn article-pdfs
  "Given an article-id, return a vector of maps that correspond to the
  files associated with article-id"
  [article-id]
  (let [pmcid-s3-id (some-> article-id article/article-pmcid article-file/pmcid->s3-id)]
    {:success true
     :files (->> (article-file/get-article-file-maps article-id)
                 (mapv #(assoc % :open-access?
                               (= (:s3-id %) pmcid-s3-id))))}))

(defn project-article-pdfs-zip
  "download all article pdfs associated with a project. name pdf by article-id"
  [project-id]
  (let [articles (project/project-article-ids project-id)
        pdfs (mapcat (fn [aid]
                       (->> (:files (article-pdfs aid))
                            (map-indexed (fn [i art]
                                           (if (= i 0)
                                             {:key (:key art) :name (format "%s.pdf" aid)}
                                             {:key (:key art) :name (format "%s-%d.pdf" aid i)})))))
                     articles)
        tmpzip (util/create-tempfile :suffix (format "%d.zip" project-id))]
    (with-open [zip (ZipOutputStream. (io/output-stream tmpzip))]
      (doseq [f pdfs]
        (util/ignore-exceptions
         (with-open [is ^java.io.Closeable (s3-file/get-file-stream (:key f) :pdf)]
           (.putNextEntry zip (ZipEntry. (str (:name f))))
           (io/copy is zip)))))
    tmpzip))

(defn dissociate-article-pdf
  "Remove the association between an article and PDF file."
  [article-id key filename]
  (if-let [s3-id (file/lookup-s3-id filename key)]
    (do (article-file/dissociate-article-pdf s3-id article-id)
        {:success true})
    {:error {:status not-found
             :message (str "No file found: " (pr-str [filename key]))}}))

(defn- process-annotation-context
  "Convert the context annotation to the one saved on the server"
  [context article-id]
  (let [text-context (:text-context context)
        article-field-match (ann/text-context-article-field-match
                             text-context article-id)]
    (cond-> context
      (not= text-context article-field-match)
      (assoc :text-context {:article-id article-id
                            :field article-field-match})
      true (select-keys [:start-offset :end-offset :text-context :client-field]))))

(defn save-article-annotation
  [project-id article-id user-id selection annotation & {:keys [pdf-key context]}]
  (db/with-clear-project-cache project-id
    (let [annotation-id (ann/create-annotation!
                         selection annotation
                         (process-annotation-context context article-id)
                         article-id)]
      (ann/associate-ann-user annotation-id user-id)
      (when pdf-key
        (let [s3-id (article-file/s3-id-from-article-key article-id pdf-key)]
          (ann/associate-ann-s3 annotation-id s3-id)))
      {:success true, :annotation-id annotation-id})))

(defn article-user-annotations [article-id]
  {:success true, :annotations (ann/user-defined-article-annotations article-id)})

(defn article-pdf-user-annotations [article-id pdf-key]
  (let [s3-id (article-file/s3-id-from-article-key article-id pdf-key)]
    {:success true, :annotations (ann/user-defined-article-pdf-annotations
                                  article-id s3-id)}))

;; todo: this needs better error handling
(defn delete-annotation! [annotation-id]
  (ann/delete-annotation! annotation-id)
  {:success true, :annotation-id annotation-id})

(defn update-annotation!
  "Update the annotation for user-id. Only users can edit their own annotations"
  [annotation-id annotation semantic-class user-id]
  (with-transaction
    (if (= user-id (ann/ann-user-id annotation-id))
      (do (ann/update-annotation! annotation-id annotation semantic-class)
          {:success true
           :annotation-id annotation-id
           :annotation annotation
           :semantic-class semantic-class})
      {:success false
       :annotation-id annotation-id
       :annotation annotation
       :semantic-class semantic-class})))

(defn pdf-download-url [article-id filename key]
  (str "/api/files/article/" article-id "/download/" key "/" filename))

(defn project-annotations [project-id]
  (->> (ann/project-annotations project-id)
       (mapv #(condp = (:datasource-name %)
                "pubmed" (assoc % :pmid (parse-integer (:external-id %)))
                %))
       (mapv #(if-not (nil? (and (:filename %)
                                 (:key %)))
                (assoc % :pdf-source (pdf-download-url
                                      (:article-id %)
                                      (:filename %)
                                      (:key %)))
                %))
       (mapv #(set/rename-keys % {:definition :semantic-class}))
       (mapv (fn [result]
               (let [text-context (get-in result [:context :text-context])
                     field (:field text-context)]
                 (if (map? text-context)
                   (assoc-in result [:context :text-context]
                             (get result (keyword field)))
                   (assoc-in result [:context :text-context]
                             text-context)))))
       (mapv #(select-keys % [:selection :annotation :semantic-class
                              :pmid :article-id :pdf-source :context :user-id]))))

(defn project-annotation-status [project-id & {:keys [user-id]}]
  (with-transaction
    (let [member? (and user-id (member/member-role? project-id user-id "member"))]
      {:status (cond-> {:project (ann/project-annotation-status project-id)}
                 member? (assoc :member (ann/project-annotation-status
                                         project-id :user-id user-id)))})))

;; todo: this needs better error handling
(defn change-project-permissions [project-id users-map]
  (assert project-id)
  (with-transaction
    (doseq [[user-id perms] (vec users-map)]
      (member/set-member-permissions project-id user-id perms))
    {:success true}))

(defn public-projects []
  {:projects (project/all-public-projects)})

(defn user-in-group-name? [user-id group-name]
  {:enabled (boolean (:enabled (group/read-user-group-name user-id group-name)))})

(defn sync-all-group-projects! [org-id]
  (let [group-projects (group/group-projects org-id :private-projects? true)]
    (doall (for [project group-projects]
             (sync-project-owners! (:project-id project) org-id)))))

(defn remove-member-from-all-group-projects! [org-id user-id]
  (let [group-projects (group/group-projects org-id :private-projects? true)]
    (doall (for [project group-projects]
             (member/remove-project-member (:project-id project) user-id)))))

(defn set-user-group!
  "Set the membership in a group as determined as determined by enabled"
  [user-id group-name enabled]
  (let [group-id (group/group-name->id group-name)]
    (with-transaction
      (if-let [user-group-id (:id (group/read-user-group-name user-id group-name))]
        (group/set-user-group-enabled! user-group-id enabled)
        (group/add-user-to-group! user-id group-id))
      ;; if enabled is true, sync the perms
      (if enabled
        (sync-all-group-projects! group-id)
        ;; ... otherwise, disable all of the member perms for the user
        (remove-member-from-all-group-projects! group-id user-id))
      ;; change any existing permissions to default permission of "member"
      (let [user-group (group/read-user-group-name user-id group-name)]
        (group/set-user-group-permissions! (:id user-group) ["member"])
        {:enabled (:enabled user-group)}))))

(defn users-in-group [group-name]
  {:users (group/read-users-in-group group-name)})

(defn user-active-in-group? [user-id group-name]
  (group/user-active-in-group? user-id group-name))

(defn read-user-public-info [user-id]
  (if-let [user (first (user/get-users-public-info [user-id]))]
    {:user user}
    {:error {:status not-found :message "That user does not exist"}}))

(defn send-invitation-email
  "Send an invitation email"
  [email project-id]
  (let [project-name (q/find-one :project {:project-id project-id} :name)]
    (sendgrid/send-template-email
     email (str "You've been invited to " project-name " as a reviewer")
     (str "You've been invited to <b>" project-name
          "</b> as a reviewer. You can view the invitation <a href='" (sysrev-base-url)
          "/user/settings/invitations'>here</a>."))))

(defn create-invitation!
  "Create an invitation from inviter to join project-id to invitee with
  optional description"
  [invitee project-id inviter & [description]]
  (let [description (or description "view-project")
        project-invitation (->> invitee
                                invitation/invitations-for-user
                                (filter #(= project-id (:project-id %)))
                                (filter #(= description (:description %))))
        email (q/get-user invitee :email)]
    (if (empty? project-invitation)
      (do
        ;; send invitation email
        (send-invitation-email email project-id)
        ;; create invitation
        {:invitation-id (invitation/create-invitation! invitee project-id inviter description)})
      {:error {:status bad-request
               :message "You can only send one invitation to a user per project"}})))

(defn read-invitations-for-admined-projects
  "Return all invitations for projects admined by user-id"
  [user-id]
  {:invitations (invitation/invitations-for-admined-projects user-id)})

(defn read-user-invitations
  "Return all invitations for user-id"
  [user-id]
  {:invitations (invitation/invitations-for-user user-id)})

(defn update-invitation!
  "Update invitation-id with accepted? value"
  [invitation-id accepted?]
  ;; user joins project when invitation is accepted
  (when accepted?
    (let [{:keys [project-id user-id]} (invitation/get-invitation invitation-id)]
      (when (nil? (member/project-member project-id user-id))
        (member/add-project-member project-id user-id))))
  (invitation/update-invitation-accepted! invitation-id accepted?)
  {:success true})

(defn user-email-addresses
  "Given a user-id, return all email addresses associated with it"
  [user-id]
  {:addresses (user/get-user-emails user-id)})

(defn change-datasource-email! [user-id]
  (let [sysrev-user (clojure.set/rename-keys (user/user-by-id user-id)
                                             {:api-token :api-key})
        datasource-account (ds-api/read-account sysrev-user)]
    (when-not (:errors datasource-account)
      (ds-api/change-account-email! sysrev-user))))

(defn verify-user-email! [user-id code]
  ;; does the code match the one associated with user?
  (let [{:keys [verify-code verified email]} (user/user-email-status user-id code)]
    (cond
      ;; this email is already verified and set as primary, for this account or another
      (user/verified-primary-email? email)
      {:error {:status precondition-failed
               :message "This email address is already verified and set as primary."}}
      verified
      ;; user email has already been verified
      {:error {:status precondition-failed
               :message "This email address has already been verified"}}
      ;; code does not match
      (not= verify-code code)
      {:error {:status precondition-failed
               :message "Verification code does not match our records"}}
      (= verify-code code)
      (do (user/verify-email! email verify-code user-id)
          ;; set this as primary when the user doesn't have any other verified email addresses
          (when (= 1 (count (->> (user/get-user-emails user-id)
                                 (filter :verified))))
            (user/set-primary-email! user-id email)
            (change-datasource-email! user-id))
          ;;provide a welcome email
          (send-welcome-email email)
          {:success true})
      :else
      {:error {:status internal-server-error
               :message "An unknown condition occured"}})))

(defn create-user-email! [user-id new-email]
  (let [current-email-entry (user/current-email-entry user-id new-email)]
    (cond
      ;; this email was already registerd to another user
      (user-by-email new-email)
      {:error {:status forbidden
               :message "This email address was already used to register an account."}}
      ;; this email doesn't exist for this user
      (not current-email-entry)
      (do (user/create-email-verification! user-id new-email)
          (send-verification-email user-id new-email)
          {:success true})
      ;; this email address has already been entered for this user
      ;; but it is not enabled, re-enable it
      (not (:enabled current-email-entry))
      (do (user/set-user-email-enabled! user-id new-email true)
          (when-not (:verified current-email-entry)
            (send-verification-email user-id new-email))
          {:success true})
      ;; this email address is already enabled
      (:enabled current-email-entry)
      {:error {:status bad-request
               :message "That email is already associated with an account."}}
      :else
      {:error {:status internal-server-error
               :message "An unknown condition occured"}})))

(defn delete-user-email! [user-id email]
  (let [current-email-entry (user/current-email-entry user-id email)]
    (cond (nil? current-email-entry)
          {:error {:status not-found
                   :message "That email address is not associated with user-id"}}
          (:principal current-email-entry)
          {:error {:status forbidden
                   :message "Primary email addresses can not be deleted"}}
          (not (:principal current-email-entry))
          (do (user/set-user-email-enabled! user-id email false)
              {:success true})
          :else
          {:error {:status bad-request
                   :messasge "An unkown error occured."}})))

(defn set-user-primary-email! [user-id email]
  (let [current-email-entry (user/current-email-entry user-id email)]
    (cond
      ;; already used for a main account
      (user-by-email email)
      {:error {:status forbidden
               :message "This email address was already used to register an account."}}
      (:principal current-email-entry)
      {:error {:status precondition-failed
               :message "This email address is already the primary email account"}}
      ;; this email is already verified and set as primary, for this account or another
      (user/verified-primary-email? email)
      {:error {:status precondition-failed
               :message "This email address is already verified and set as primary for an account."}}
      (not (:verified current-email-entry))
      {:error {:status precondition-failed
               :message "This email address has not been verified. Only verified email addresses can be set as primary"}}
      (:verified current-email-entry)
      (do (user/set-primary-email! user-id email)
          (change-datasource-email! user-id)
          {:success true})
      :else
      {:error {:status internal-server-error
               :message "An unknown condition occured"}})))

(defn user-projects
  "Return a list of user projects for user-id, including non-public projects when self? is true"
  [user-id self?]
  (with-transaction
    (let [projects (->> ((if self? user/user-projects user/user-public-projects)
                         user-id [:p.name :p.settings])
                        (mapv #(assoc % :project-owner
                                      (project/get-project-owner (:project-id %)))))
          labeled-summary (user/projects-labeled-summary user-id)
          annotations-summary (user/projects-annotated-summary user-id)]
      {:projects (vals (merge-with merge
                                   (index-by :project-id projects)
                                   (index-by :project-id labeled-summary)
                                   (index-by :project-id annotations-summary)))})))

(defn update-user-introduction! [user-id introduction]
  (user/update-user-introduction! user-id introduction)
  {:success true})

(defn create-profile-image! [user-id file filename]
  (let [image (user-image/save-user-profile-image user-id file filename)]
    {:success true :key (:key image)}))

(defn read-profile-image
  "Return the currently active profile image for user"
  [user-id]
  (if-let [{:keys [key filename] :as _x} (user-image/user-active-profile-image user-id)]
    (-> (response/response (s3-file/get-file-stream key :image))
        (response/header "Content-Disposition"
                         (format "attachment: filename=\"%s\"" filename)))
    {:error {:status not-found
             :message "No profile image associated with user"}}))

(defn read-profile-image-meta
  "Read the current profile image meta data"
  [user-id]
  {:success true
   :meta (-> (some-> user-id user-image/user-active-profile-image :meta json/read-json)
             (or {}))})

(defn create-avatar! [user-id file filename meta]
  (user-image/save-user-avatar-image user-id file filename meta)
  {:success true})

(defn read-avatar [user-id]
  (or (when-let [{:keys [key filename]} (user-image/user-active-avatar-image user-id)]
        (-> (response/response (s3-file/get-file-stream key :image))
            (response/header "Content-Disposition"
                             (format "attachment: filename=\"%s\"" filename))))
      (when-let [gravatar-img (user-image/gravatar-image-data (q/get-user user-id :email))]
        (-> (response/response gravatar-img)
            (response/header "Content-Disposition"
                             (format "attachment: filename=\"%d-gravatar.jpeg\"" user-id))))
      (-> (response/response (-> "public/default_profile.jpeg" io/resource io/input-stream))
          (response/header "Content-Disposition"
                           (format "attachment: filename=\"default-profile.jpeg\"")))))

(defn read-orgs
  "Return all organizations user-id is a member of"
  [user-id]
  (with-transaction
    {:orgs (->> (group/read-groups user-id)
                (filterv #(not= (:group-name %) "public-reviewer"))
                (mapv #(assoc % :member-count (-> (group/group-id->name (:group-id %))
                                                  (group/read-users-in-group)
                                                  count)))
                (mapv #(assoc % :plan (plans/group-current-plan (:group-id %)))))}))

(defn validate-org-name [org-name]
  (cond (group/group-name->id org-name)
        ;; alredy exists
        {:error {:status conflict
                 :message (str "An organization with the name '" org-name "' already exists."
                               " Please try using another name.")}}
        (str/blank? org-name)
        {:error {:status bad-request
                 :message (str "Organization names can't be blank!")}}
        :else {:valid true}))

(defn create-org! [user-id org-name]
  (let [validation-result (validate-org-name org-name)]
    (if (:error validation-result)
      validation-result
      (with-transaction
        ;; create the group
        (let [new-org-id (group/create-group! org-name)
              user (q/get-user user-id)
              _ (group/create-group-stripe-customer! new-org-id user)
              stripe-id (group/group-stripe-id new-org-id)]
          ;; set the user as group owner
          (group/add-user-to-group! user-id (group/group-name->id org-name) :permissions ["owner"])
          (stripe/create-subscription-org! new-org-id stripe-id)
          {:success true, :id new-org-id :user-id user-id})))))

(defn create-org-pro!
  "Create a org for user-id using plan and payment method, all in one shot"
  [user-id org-name plan payment-method]
  ;; make sure we can create this org
  (if-let [{:keys [status message]} (:error (validate-org-name org-name))]
    {:error {:status status
             :org-error {:message message}}}
    ;; create the org..
    (let [{:keys [id]} (create-org! user-id org-name)]
      ;; try to update the payment info
      (if-let [{:keys [message status]} (:error (update-org-stripe-payment-method! id payment-method))]
        {:error {:status status
                 :stripe-error {:message message}}}
        ;; try to register the org
        (if-let [{:keys [status message]} (:error (subscribe-org-to-plan id plan))]
          {:error {:status status
                   :plan-error {:message message}}}
          ;; finally, if everything went through ok, return the org-id
          {:success true :id id})))))

(defn search-users [{:keys [exact? term]}]
  {:success true
   :users
   (if exact?
     (when (and (s/valid? ::su/username term) (seq term))
       (-> term user/user-by-username :user-id vector user/get-users-public-info))
     (user/search-users term))})

(defn set-user-group-permissions! [user-id org-id permissions]
  (with-transaction
    (if-let [user-group-id (:id (group/read-user-group-name
                                 user-id (group/group-id->name org-id)))]
      (let [group-perm-id (group/set-user-group-permissions! user-group-id permissions)]
        (sync-all-group-projects! org-id)
        {:group-perm-id group-perm-id})
      {:error {:message (str "user-id: " user-id " is not part of org-id: " org-id)}})))

(defn group-projects [group-id & {:keys [private-projects?]}]
  {:projects (->> (group/group-projects group-id :private-projects? private-projects?)
                  (map #(assoc %
                               :member-count (project/member-count (:project-id %))
                               :admins (project/get-project-admins (:project-id %))
                               :last-active (project/last-active (:project-id %)))))})

(defn subscription-lapsed?
  "Is the project private with a lapsed subscription?"
  [project-id]
  (with-transaction
    (when project-id
      (let [{:keys [public-access]} (project/project-settings project-id)]
        (and (not public-access)
             (not (project-unlimited-access? project-id)))))))

(defn search-site
  "Search the site with query q at pagenumber p"
  [q p]
  (let [p (or p 1)
        page-size 10
        max-pages 10
        max-results (* page-size max-pages)
        projects (project/search-projects q :limit max-results)
        projects-partition (partition-all page-size projects)
        users (user/search-users q :limit max-results)
        users-partition (partition-all page-size users)
        orgs (group/search-groups q :limit max-results)
        orgs-partition (partition-all page-size orgs)]
    {:results {:projects {:items (nth projects-partition (- p 1) [])
                          :count (count projects)}
               :users {:items (nth users-partition (- p 1) [])
                       :count (count users)}
               :orgs {:items (nth orgs-partition (- p 1) [])
                      :count (count orgs)}}}))

(defonce stripe-hooks-atom nil)

(defn handle-stripe-hooks
  [request]
  (println "I was triggered")
  (reset! stripe-hooks-atom request)
  {})

(defn clone-project
  "Given a src-project-id, clone it with user-id as the owner and return the new project-id. This copies the Project Description, Label Definitions and all article sources."
  [src-project-id]
  (with-transaction
    (let [project-name (:name (q/find-one :project {:project-id src-project-id}))
          project-description (description/read-project-description src-project-id)
          dest-project-id (:project-id (project/create-project project-name :parent-project-id src-project-id))
          ;; copy project sources
          src-dest-source-map (clone/copy-article-source-defs! src-project-id dest-project-id)]
      ;; copy the labels
      (clone/copy-project-label-defs src-project-id dest-project-id)
      ;; copy article sources
      (clone/copy-articles! src-dest-source-map src-project-id dest-project-id)
      ;; set project description
      (description/set-project-description! dest-project-id project-description)
      dest-project-id)))

(defn clone-authorized?
  [{:keys [src-project-id user-id]}]
  (cond
    ;; public projects are cloneable
    (:public-access (project/project-settings src-project-id))
    true
    ;; a project admin can clone the project
    (and (not (:public-access (project/project-settings src-project-id)))
         (project/project-admin-or-owner? user-id src-project-id))
    true
    ;; otherwise, false
    :else false))

(defn clone-project-for-user! [{:keys [src-project-id user-id] :as args}]
  (if (clone-authorized? args)
    (with-transaction
      (let [dest-project-id (clone-project src-project-id)]
        ;; set the user-id as owner
        (member/add-project-member dest-project-id user-id
                                   :permissions ["member" "admin" "owner"])
        {:dest-project-id dest-project-id}))
    {:error {:status forbidden
             :message "You don't have permission to clone that project"}}))

(defn clone-project-for-org! [{:keys [src-project-id user-id org-id] :as args}]
  (if (clone-authorized? args)
    (with-transaction
      (let [dest-project-id (clone-project src-project-id)]
        ;; add the project to the group
        (group/create-project-group! dest-project-id org-id)
        (sync-project-owners! dest-project-id org-id)
        (notification/create-notification
         {:adding-user-id user-id
          :group-id org-id
          :group-name (q/find-one :groups {:group-id org-id} :group-name)
          :project-id dest-project-id
          :project-name (q/find-one :project {:project-id dest-project-id} :name)
          :type :group-has-new-project})
        {:dest-project-id dest-project-id}))
    {:error {:status forbidden
             :message "You don't have permission to clone that project"}}))

(defn graphql-request
  "Make a request against out own GraphQL API, using our own dev
  key. This allows for internal use of GraphQL"
  [query]
  (let [body (-> (mock/request :post "/graphql")
                 (mock/header "Authorization" (str "Bearer " (ds-api/ds-auth-key)))
                 (mock/json-body {:query query})
                 ((graphql-handler @sysrev-schema))
                 :body)]
    (try (json/read-str body :key-fn keyword)
         (catch Exception _
           body))))

(defn project-json [project-id]
  (let [req [[:project {:id project-id}
              [:name :id :date_created
               [:labelDefinitions
                [:consensus :enabled :name :question :required :type]]
               [:groupLabelDefinitions
                [:enabled :name :question :required :type
                 [:labels [:consensus :enabled :name :question :required :type]]]]
               [:articles
                [:datasource_id :enabled :id :uuid
                 [:groupLabels
                  [[:answer
                    [:id :answer :name :question :required :type]]
                   :confirmed :consensus :created :id :name :question
                   :required :updated :type
                   [:reviewer [:id :name]]]]
                 [:labels
                  [:answer :confirmed :consensus :created :id :name :question
                   :required :updated :type
                   [:reviewer [:id :name]]]]]]]]]
        resp (graphql-request (util/gquery req))]
    (if (:errors resp)
      {:status internal-server-error
       :errors (:errors resp)}
      (:data resp))))

(defn managed-review-request [request]
  (let [name        (:name  (:body request))
        email       (:email (:body request))
        description (:description (:body request))]
    (sendgrid/send-template-email
     "tom@insilica.co"
     (format "%s - MANAGED REVIEW REQUEST " name)
     (format "Name %s email %s\n%s." name email description))
    {:success true}))

(defn send-bulk-invitations
  "Send invitation emails in bulk"
  [project-id emails]
  (let [project (q/find-one :project {:project-id project-id})
        project-name (:name project)
        project-hash (some-> project :project-uuid util/short-uuid)
        invite-url (str (sysrev-base-url) "/register/" project-hash)
        responses (->> emails
                       (filter util/email?)
                       set ; remove duplicates
                       (map (fn [email]
                              (sendgrid/send-template-email
                               email (str "You've been invited to " project-name " as a reviewer")
                               (str "You've been invited to <b>" project-name
                                    "</b> as a reviewer. You can view the invitation <a href='" invite-url "'>here</a>.")))))
        response-count (count responses)
        failure-count (->> responses (filter (comp not :success)) count)
        success? (zero? failure-count)]
    {:success success?
     :message (if success?
                (str response-count " invitation(s) successfully sent!")
                (str failure-count " out of " (count responses) " invitation(s) failed to be sent"))}))


(defn create-project-member-gengroup [project-id gengroup-name gengroup-description]
  (cond (gengroup/read-project-member-gengroups project-id :gengroup-name gengroup-name)
        {:error {:status conflict
                 :message (str "A group with the name '" gengroup-name "' already exists for this project."
                               " Please try using another name.")}}
        :else (do
                (gengroup/create-project-member-gengroup! project-id gengroup-name gengroup-description)
                {:success true})))

(defn toggle-developer-account!
  [user-id enabled?]
  (let [sysrev-user (clojure.set/rename-keys (user/user-by-id user-id)
                                             {:api-token :api-key})
        datasource-account (ds-api/read-account sysrev-user)]
    (cond (not (stripe/user-has-pro? user-id))
          (if (user/dev-user? user-id)
            (do (user/change-user-setting user-id :dev-account-enabled? enabled?)
                {:error {:status forbidden
                         :message "Datasource API access not enabled: Account does not have a Pro subscription."}})
            {:error {:status forbidden
                     :message "Your account does not have a Pro subscription. Upgrade to enable API access."}} )
          (medley/find-first #(= (:message %) "Account Does Not Exist") (:errors datasource-account))
          ;; the account does not exist
          (let [{:keys [pw-encrypted-buddy email api-token]} (user/user-by-id user-id)]
            (ds-api/create-account! {:email email :password pw-encrypted-buddy :api-key api-token})
            (user/change-user-setting user-id :dev-account-enabled? enabled?)
            (ds-api/toggle-account-enabled! sysrev-user enabled?))
          :else
          (do (user/change-user-setting user-id :dev-account-enabled? enabled?)
              (ds-api/toggle-account-enabled! sysrev-user enabled?)))))

(defn datasource-account-enabled?
  [user-id]
  (:dev-account-enabled? (user/user-settings user-id)))

(defn change-datasource-password! [user-id]
  (let [sysrev-user (clojure.set/rename-keys (user/user-by-id user-id)
                                             {:api-token :api-key
                                              :pw-encrypted-buddy :password})
        datasource-account (ds-api/read-account sysrev-user)]
    (when-not (:errors datasource-account)
      (ds-api/change-account-password! sysrev-user))))

(defn epoch-millis->LocalDateTime ^java.time.LocalDateTime [millis]
  (java.time.LocalDateTime/ofEpochSecond
   (quot millis 1000)
   (* 1000 (rem millis 1000))
   java.time.ZoneOffset/UTC))

(defn at-start-of-day [^java.time.ZonedDateTime zdt]
  (-> zdt (.withHour 0) (.withMinute 0) (.withSecond 0) (.withNano 0)))

(defn user-notifications-by-day*
  "We first query for notifications up to the specified limit. Then we
  find the day of the last notification, and make a second query for the
  rest of the notifications on that day.

  This avoids complications introduced by combining notifications. Since
  combination happens per day, we shouldn't have any surprises as long
  as we send all notifications for each day at the same time."
  [user-id {:keys [created-after limit]}]
  (with-transaction
    (when-let [subscriber-id (notification/subscriber-for-user
                              user-id :returning :subscriber-id)]
      (let [ntfs (notification/notifications-for-subscriber
                  subscriber-id :where (when created-after
                                         [:< :created created-after])
                  :limit limit)
            last-timestamp (-> ntfs last :created)
            start-of-day (when last-timestamp
                           (-> last-timestamp
                               .toInstant
                               (.atZone (java.time.ZoneId/of "UTC"))
                               at-start-of-day .toInstant
                               java.sql.Timestamp/from))
            ntfs2 (when last-timestamp
                    (notification/notifications-for-subscriber
                     subscriber-id :where
                     [:and [:>= :created start-of-day]
                      [:<= :created last-timestamp]]))]
        {:notifications (dedupe (concat ntfs ntfs2))
         :start-of-day start-of-day}))))

(defn user-notifications-by-day [user-id {:keys [created-after limit]}]
  (let [created-after (when created-after
                        (some-> created-after parse-long
                                epoch-millis->LocalDateTime
                                (.atZone (java.time.ZoneId/of "UTC"))
                                .toInstant java.sql.Timestamp/from))
        limit (-> (some-> limit parse-long)
                  (or 50) (min 50))
        {:keys [notifications start-of-day]}
        #__ (user-notifications-by-day*
             user-id {:created-after created-after :limit limit})]
    {:success true
     :notifications (combine-notifications
                     (fn [{:keys [created]}]
                       [(.getYear created) (.getMonth created) (.getDate created)])
                     notifications)
     :next-created-after start-of-day}))

(defn user-notifications-new [user-id & [{:keys [limit]}]]
  (with-transaction
    (let [subscriber-id (notification/subscriber-for-user
                         user-id :create? true :returning :subscriber-id)
          limit (-> (some-> limit parse-long)
                    (or 50) (min 50))]
      {:success true
       :notifications
       (->> (notification/notifications-for-subscriber
             subscriber-id :where [:= :consumed nil] :limit 50)
            (concat (notification/unviewed-system-notifications
                     subscriber-id :limit limit))
            combine-notifications
            (take limit))})))

(defn user-notifications-set-consumed [notification-ids user-id]
  {:success true
   :row-count (with-transaction
                (notification/update-notifications-consumed
                 (notification/subscriber-for-user
                  user-id
                  :create? true
                  :returning :subscriber-id)
                 notification-ids))})

(defn user-notifications-set-viewed [notification-ids user-id]
  {:success true
   :row-count (with-transaction
                (notification/update-notifications-viewed
                 (notification/subscriber-for-user
                  user-id
                  :create? true
                  :returning :subscriber-id)
                 notification-ids))})

(defn import-label [share-code project-id]
  (let [share-data (enc/decrypt share-code)
        label-id (uuid-from-string (:label-id share-data))
        existing-label (q/find-one :label {:project-id project-id
                                           :global-label-id label-id})]
    (if existing-label
      {:success false
       :message "Label already imported in this project."}
      (do
        (label/import-label share-code project-id)
        {:success true
         :labels (project/project-labels project-id true)}))))

(defn detach-label [project-id label-id]
  (label/detach-label project-id label-id)
  {:success true
   :labels (project/project-labels project-id true)})
