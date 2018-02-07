(ns sysrev.db.users
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [sysrev.shared.spec.core :as sc]
   [sysrev.shared.spec.users :as su]
   [sysrev.db.core :refer
    [do-query do-execute sql-now to-sql-array with-transaction to-jsonb]]
   [sysrev.payments :as payments]
   [sysrev.shared.util :refer [map-values in?]]
   [sysrev.util :as util]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [buddy.core.hash :as hash]
   [buddy.core.codecs :as codecs]
   buddy.hashers
   crypto.random
   [sysrev.db.core :as db]
   [sysrev.db.queries :as q]
   [sysrev.db.project :refer [add-project-member]])
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
        (boolean
         (or (re-find #"\+test.*\@" email)
             (re-find #"\@sysrev\.us$" email)
             (re-find #"\@insilica\.co$" email)))
        permissions (cond
                      (:permissions opts) (:permissions opts)
                      test-email? ["admin"]
                      :else permissions)
        entry
        (cond->
            {:email email
             :pw-encrypted-buddy (encrypt-password password)
             :verify-code (crypto.random/hex 16)
             :permissions (to-sql-array "text" permissions)
             :default-project-id project-id
             ;; TODO: implement email verification
             :verified true
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
  (-> (delete-from :web-user)
      (where [:= :user-id user-id])
      do-execute)
  (db/clear-query-cache)
  nil)

(defn verify-user-email [verify-code]
  (-> (sqlh/update :web-user)
      (sset {:verified true})
      (where [:= :verify-code verify-code])
      do-execute))

(defn change-user-id [current-id new-id]
  (-> (sqlh/update :web-user)
      (sset {:user-id new-id})
      (where [:= :user-id current-id])
      do-execute)
  (db/clear-query-cache))

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

(defn user-password-reset-url [user-id]
  (when-let [reset-code
             (-> (select :reset-code)
                 (from :web-user)
                 (where [:= :user-id user-id])
                 do-query first :reset-code)]
    (format "https://sysrev.us/reset-password/%s" reset-code)))

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
              :settings)
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
        projects
        (-> (select :p.project-id :p.name :p.date-created :m.join-date
                    [:p.enabled :project-enabled]
                    [:m.enabled :member-enabled])
            (from [:project-member :m])
            (join [:project :p]
                  [:= :p.project-id :m.project-id])
            (where [:and
                    [:= :m.user-id user-id]
                    [:= :p.enabled true]
                    [:= :m.enabled true]])
            (order-by :p.date-created)
            (->> do-query
                 (mapv #(assoc % :member? true))))
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
                   (mapv #(assoc % :member? false)))))]
    {:projects (->> [projects all-projects]
                    (apply concat)
                    vec)}))

(defn create-sysrev-stripe-customer!
  "Create a stripe customer from user"
  [user]
  (let [{:keys [email user-uuid user-id]} user
        stripe-response (payments/create-customer! email (str user-uuid))
        stripe-customer-id (:id stripe-response)]
    (if-not (nil? stripe-customer-id)
      (try
        (-> (sqlh/update :web-user)
            (sset {:stripe-id stripe-customer-id})
            (where [:= :user-id user-id])
            do-execute)
        (catch Throwable e
          (let [error-message (.getMessage e)]
            (log/error (str "Error in " (util/current-function-name) ": " error-message))
            {:error error-message}))
        (finally {:success true}))
      (let [error-message (str "No customer id returned by stripe.com for email: " email " and uuid: " user-uuid)]
        (log/error (str "Error in " (util/current-function-name) ": " error-message))
        {:error error-message}))))
