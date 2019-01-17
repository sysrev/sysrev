(ns sysrev.db.users
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            buddy.hashers
            crypto.random
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db
             :refer [do-query do-execute with-transaction
                     sql-now to-sql-array to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :refer [add-project-member]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.users :as su]
            [sysrev.stripe :as stripe]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values in?]])
  (:import java.util.UUID))

(defn all-users
  "Returns seq of short info on all users, for interactive use."
  []
  (->>
   (-> (select :*)
       (from :web-user)
       do-query)
   (map
    #(select-keys % [:user-id :email :permissions]))))

(defn get-user-by-email [email]
  (-> (select :*)
      (from :web-user)
      (where [:= (sql/call :lower :email) (sql/call :lower email)])
      do-query
      first))

(defn get-user-by-id [user-id]
  (-> (select :*)
      (from :web-user)
      (where [:= :user-id user-id])
      do-query
      first))

(defn get-users-public-info
  [user-ids]
  "Given a coll of user-ids, return a coll of maps that represent the publicly viewable information for each user-id"
  (-> (select :user-id :email :date_created :username)
      (from :web-user)
      (where [:in :web-user.user_id user-ids])
      do-query))

(defn get-user-by-reset-code [reset-code]
  (assert (string? reset-code))
  (-> (select :*)
      (from :web-user)
      (where [:= :reset-code reset-code])
      do-query
      first))

(defn get-user-by-api-token [api-token]
  (assert (string? api-token))
  (-> (select :*)
      (from :web-user)
      (where [:= :api-token api-token])
      do-query first))

(defn generate-api-token []
  (->> (crypto.random/bytes 16)
       hash/sha256
       vec
       (take 12)
       byte-array
       codecs/bytes->hex))

(defn encrypt-password [password]
  (buddy.hashers/encrypt
   password {:algorithm :bcrypt+sha512
             :iterations 6
             :salt (crypto.random/bytes 16)}))

(defn create-user [email password &
                   {:keys [project-id user-id permissions]
                    :or {permissions ["user"]}
                    :as opts}]
  (let [test-email?
        (and (not= (:profile env) :prod)
             (boolean
              (or (re-find #"\+test.*\@" email)
                  (re-find #"\@sysrev\.us$" email)
                  (re-find #"\@insilica\.co$" email))))
        permissions (cond
                      (:permissions opts) (:permissions opts)
                      test-email? ["admin"]
                      :else permissions)
        entry
        (cond->
            {:email email
             :pw-encrypted-buddy (encrypt-password password)
             :verify-code nil ;; (crypto.random/hex 16)
             :permissions (to-sql-array "text" permissions)
             :default-project-id project-id
             :date-created (sql-now)
             :user-uuid (UUID/randomUUID)
             :api-token (generate-api-token)}
            user-id (assoc :user-id user-id))]
    (when project-id
      (assert (-> (q/query-project-by-id project-id [:project-id])
                  :project-id)))
    (let [{:keys [user-id] :as user}
          (-> (insert-into :web-user)
              (values [entry])
              (returning :*)
              do-query
              first)]
      (when project-id
        (add-project-member project-id user-id))
      (db/clear-query-cache)
      user)))

(defn set-user-password [email new-password]
  (-> (sqlh/update :web-user)
      (sset {:pw-encrypted-buddy (encrypt-password new-password)})
      (where [:= (sql/call :lower :email) (sql/call :lower email)])
      do-execute))

(defn set-user-permissions
  "Change the site permissions for a user account."
  [user-id permissions]
  (try
    (-> (sqlh/update :web-user)
        (sset {:permissions (to-sql-array "text" permissions)})
        (where [:= :user-id user-id])
        (returning :user-id :permissions)
        do-query)
    (finally
      (db/clear-query-cache))))

(defn set-user-default-project [user-id project-id]
  (-> (sqlh/update :web-user)
      (sset {:default-project-id project-id})
      (where [:= :user-id user-id])
      (returning :user-id :default-project-id)
      do-query))

(defn valid-password? [email password-attempt]
  (let [entry (get-user-by-email email)
        encrypted-password (:pw-encrypted-buddy entry)]
    (boolean
     (and entry
          encrypted-password
          (buddy.hashers/check password-attempt encrypted-password)))))

(defn delete-user [user-id]
  (assert (integer? user-id))
  (try
    (q/delete-by-id :web-user :user-id user-id)
    (finally
      (db/clear-query-cache))))

(defn delete-user-by-email [email]
  (assert (string? email))
  (try
    (q/delete-by-id :web-user :email email)
    (finally
      (db/clear-query-cache))))

(defn create-password-reset-code [user-id]
  (-> (sqlh/update :web-user)
      (sset {:reset-code (crypto.random/hex 16)})
      (where [:= :user-id user-id])
      do-execute))

(defn clear-password-reset-code [user-id]
  (-> (sqlh/update :web-user)
      (sset {:reset-code nil})
      (where [:= :user-id user-id])
      do-execute))

(defn user-password-reset-url
  [user-id & {:keys [url-base]
              :or {url-base "https://sysrev.com"}}]
  (when-let [reset-code
             (-> (select :reset-code)
                 (from :web-user)
                 (where [:= :user-id user-id])
                 do-query first :reset-code)]
    (format "%s/reset-password/%s" url-base reset-code)))

(defn user-settings [user-id]
  (into {}
        (-> (select :settings)
            (from :web-user)
            (where [:= :user-id user-id])
            do-query first :settings)))

(defn change-user-setting [user-id setting new-value]
  (let [user-id (q/to-user-id user-id)
        cur-settings (-> (select :settings)
                         (from :web-user)
                         (where [:= :user-id user-id])
                         do-query first :settings)
        new-settings (assoc cur-settings setting new-value)]
    (assert (s/valid? ::su/settings new-settings))
    (-> (sqlh/update :web-user)
        (sset {:settings (to-jsonb new-settings)})
        (where [:= :user-id user-id])
        do-execute)
    new-settings))

(defn user-identity-info
  "Returns basic identity info for user."
  [user-id & [self?]]
  (-> (select :user-id
              :user-uuid
              :email
              :verified
              :permissions
              :settings
              :default-project-id)
      (from :web-user)
      (where [:= :user-id user-id])
      do-query
      first))

(defn user-self-info
  "Returns a map of values with various user account information.
  This result is sent to client for the user's own account upon login."
  [user-id]
  (let [uperms (:permissions (get-user-by-id user-id))
        admin? (in? uperms "admin")
        project-url-ids
        (-> (select :purl.url-id :purl.project-id)
            (from [:project-member :m])
            (join [:project :p]
                  [:= :p.project-id :m.project-id])
            (merge-join [:project-url-id :purl]
                        [:= :purl.project-id :p.project-id])
            (where [:and
                    [:= :m.user-id user-id]
                    [:= :p.enabled true]
                    [:= :m.enabled true]])
            (order-by [:purl.date-created :desc])
            (->> do-query
                 (group-by :project-id)
                 (map-values #(mapv :url-id %))))
        projects
        (-> (select :p.project-id :p.name :p.date-created
                    :m.join-date :m.access-date
                    [:p.enabled :project-enabled]
                    [:m.enabled :member-enabled]
                    [:m.permissions :permissions])
            (from [:project-member :m])
            (join [:project :p]
                  [:= :p.project-id :m.project-id])
            (where [:and
                    [:= :m.user-id user-id]
                    [:= :p.enabled true]
                    [:= :m.enabled true]])
            (order-by [:m.access-date :desc :nulls :last]
                      [:p.date-created])
            (->> do-query
                 (sort-by
                  (fn [{:keys [access-date date-created]}]
                    [(if access-date
                       (tc/to-epoch access-date) 0)
                     (- (tc/to-epoch date-created))]))
                 reverse
                 (mapv #(assoc % :member? true
                               :url-ids (get project-url-ids
                                             (:project-id %))))))
        self-project-ids (->> projects (map :project-id))
        all-projects
        (when admin?
          (-> (select :p.project-id :p.name :p.date-created
                      [:p.enabled :project-enabled])
              (from [:project :p])
              (where [:and [:= :p.enabled true]])
              (order-by :p.date-created)
              (->> do-query
                   (filterv #(not (in? self-project-ids (:project-id %))))
                   (mapv #(assoc % :member? false
                                 :url-ids (get project-url-ids
                                               (:project-id %)))))))]
    {:projects (->> [projects all-projects]
                    (apply concat)
                    vec)}))

(defn create-sysrev-stripe-customer!
  "Create a stripe customer from user"
  [user]
  (let [{:keys [email user-uuid user-id]} user
        stripe-response (stripe/create-customer! email (str user-uuid))
        stripe-customer-id (:id stripe-response)]
    (if-not (nil? stripe-customer-id)
      (try
        (do (-> (sqlh/update :web-user)
                (sset {:stripe-id stripe-customer-id})
                (where [:= :user-id user-id])
                do-execute)
            {:success true})
        (catch Throwable e
          {:error {:message "Exception in create-sysrev-stripe-customer!"
                   :exception e}}))
      {:error {:message
               (str "No customer id returned by stripe.com for email: "
                    email " and uuid: " user-uuid)}})))

(defn set-user-default-project [user-id project-id]
  (-> (sqlh/update :web-user)
      (sset {:default-project-id project-id})
      (where [:= :user-id user-id])
      do-execute))

(defn update-member-access-time [user-id project-id]
  (-> (sqlh/update :project-member)
      (sset {:access-date (sql-now)})
      (where [:and
              [:= :user-id user-id]
              [:= :project-id project-id]])
      do-execute))

(defn create-web-user-stripe-acct [stripe-acct user-id]
  (-> (insert-into :web_user_stripe_acct)
      (values [{:stripe-acct stripe-acct
                :user-id user-id}])
      do-execute))

(defn user-stripe-account
  [user-id]
  (-> (select :*)
      (from :web_user_stripe_acct)
      (where [:= :user-id user-id])
      do-query
      first))

(defn get-group-id
  "Given a group-name, get the group-id associated with it"
  [group-name]
  (-> (select :id)
      (from :groups)
      (where [:= :group-name group-name])
      do-query first :id))

(defn create-web-user-group!
  "Create a group-name for user-id in web-user-group"
  [user-id group-name]
  (-> (insert-into :web_user_group)
      (values [{:user-id user-id
                :group-id (get-group-id group-name)}])
      do-execute))

(defn read-web-user-group-name
  "Read the id for the web-user-group for user-id and group-name"
  [user-id group-name]
  (-> (select :id :active)
      (from :web_user_group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id (get-group-id group-name)]])
      do-query
      first))

(defn update-web-user-group!
  "Set the boolean active? on group-id"
  [group-id active?]
  (-> (sqlh/update :web-user-group)
      (sset {:active active?
             :updated (sql-now)})
      (where [:= :group-id group-id])
      do-execute))

(defn read-users-in-group
  "Return all of the users in group-name"
  [group-name]
  (let [users-in-group (-> (select :user-id)
                           (from :web-user-group)
                           (where [:and
                                   [:= :active true]
                                   [:= :group-id (get-group-id group-name)]])
                           do-query
                           (->> (map :user-id)))]
    (if-not (empty? users-in-group)
      (get-users-public-info users-in-group)
      [])))

(defn user-active-in-group?
  "Is the user-id active in group-name?"
  [user-id group-name]
  (-> (select :active)
      (from :web-user-group)
      (where [:and
              [:= :user-id user-id]
              [:= :group-id (get-group-id group-name)]])
      do-query
      first
      boolean))

(defn create-email-verification!
  [user-id email & {:keys [principal] :or {principal false}}]
  (-> (insert-into :web-user-email)
      (values [{:user-id user-id
                :email email
                :verify_code (crypto.random/hex 16)
                :principal principal}])
      do-execute))

(defn web-user-email
  [user-id verify-code]
  (-> (select :verify_code :verified :email)
      (from :web_user_email)
      (where [:and
              [:= :user-id user-id]
              [:= :verify-code verify-code]])
      do-query first))

(defn current-email-entry
  [user-id email]
  (-> (select :*)
      (from :web_user_email)
      (where [:and
              [:= :user-id user-id]
              [:= :email email]])
      do-query first))

;; needed in auth.clj
(defn primary-email-verified?
  "Is the primary email for this user verified?"
  [user-id]
  (let [{:keys [email]} (get-user-by-id user-id)]
    (-> (select :verified)
        (from :web_user_email)
        (where [:and
                [:= :user-id user-id]
                [:= :email email]
                [:= :principal true]])
        do-query first :verified boolean)))

#_(defn email-verified?
  "Has this email address already been verified?"
  [email]
  (-> (select :verified)
      (from :web-user-email)
      (where [:and
              [:= :email email]
              [:= :verified true]])
      do-query first :verified boolean))

#_(defn email-exists-for-user-id?
  "Does user-id already have an entry for email?"
  [user-id email]
  (-> (select :verified)
      (from :web-user-email)
      (where [:and
              [:= :user-id user-id]
              [:= :email email]])
      do-query empty? not))

(defn set-primary-email!
  "Given an email, set it as the primary email address for user-id. This assumes that the email address has been confirmed"
  [user-id email]
  ;; set all web-user-email principal to false for this user-id
  ;; note: this will eventually change
  (with-transaction
    (-> (sqlh/update :web-user-email)
        (sset {:principal false
               :updated (sql-now)})
        (where [:= :user-id user-id])
        do-execute)
    (-> (sqlh/update :web-user-email)
        (sset {:principal true
               :updated (sql-now)})
        (where [:and
                [:= :user-id user-id]
                [:= :email email]])
        do-execute)
    ;; update the user
    (-> (sqlh/update :web-user)
        (sset {:verified true
               :email email})
        (where [:= :user-id user-id])
        do-execute)))

(defn verify-email! [email verify-code user-id]
  (-> (sqlh/update :web-user-email)
      (sset {:verified true
             :updated (sql-now)})
      (where [:and
              [:= :verify-code verify-code]
              [:= :email email]
              [:= :user-id user-id]])
      do-execute))

(defn read-email-addresses
  [user-id]
  (-> (select :*)
      (from :web-user-email)
      (where [:and [:= :user-id user-id]])
      do-query
      (->> (filter :active))))

(defn set-active-field-email!
  [user-id email active]
  (-> (sqlh/update :web-user-email)
      (sset {:active active})
      (where [:and
              [:= :email email]
              [:= :user-id user-id]])
      do-execute))

(defn verified-primary-email?
  "Is this email already primary and verified?"
  [email]
  (-> (select :email)
      (from :web-user-email)
      (where [:and
              [:= :email email]
              [:= :principal true]
              [:= :verified true]])
      do-query empty? not))

(defn read-email-verification-code
  [user-id email]
  (-> (select :verify-code)
      (from :web-user-email)
      (where [:and
              [:= :user-id user-id]
              [:= :email email]])
      do-query first))
