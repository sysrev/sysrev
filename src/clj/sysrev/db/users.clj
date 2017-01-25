(ns sysrev.db.users
  (:require [sysrev.db.core :refer
             [do-query do-execute sql-now to-sql-array do-transaction]]
            [sysrev.util :refer [in? map-values]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
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
             :user-uuid (UUID/randomUUID)}
          user-id (assoc :user-id user-id))]
    (when project-id
      (assert (-> (q/query-project-by-id project-id [:project-id])
                  :project-id)))
    (let [user-id (-> (insert-into :web-user)
                      (values [entry])
                      (returning :user-id)
                      do-query
                      first :user-id)]
      (when project-id
        (add-project-member project-id user-id)))
    (db/clear-query-cache)
    true))

(defn set-user-password [email new-password]
  (-> (sqlh/update :web-user)
      (sset {:pw-encrypted-buddy (encrypt-password new-password)})
      (where [:= (sql/call :lower :email) (sql/call :lower email)])
      do-execute))

(defn set-user-permissions
  "Change the site permissions for a user account."
  [user-id permissions]
  (db/clear-user-cache user-id)
  (-> (sqlh/update :web-user)
      (sset {:permissions (to-sql-array "text" permissions)})
      (where [:= :user-id user-id])
      (returning :user-id :permissions)
      do-query))

(defn set-user-default-project [user-id project-id]
  (db/clear-user-cache user-id)
  (db/clear-query-cache)
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
  (db/clear-query-cache)
  (assert (integer? user-id))
  (-> (delete-from :web-user)
      (where [:= :user-id user-id])
      do-execute)
  nil)

(defn verify-user-email [verify-code]
  (db/clear-query-cache)
  (-> (sqlh/update :web-user)
      (sset {:verified true})
      (where [:= :verify-code verify-code])
      do-execute))

(defn change-user-id [current-id new-id]
  (db/clear-query-cache)
  (-> (sqlh/update :web-user)
      (sset {:user-id new-id})
      (where [:= :user-id current-id])
      do-execute))

(defn create-password-reset-code [user-id]
  (db/clear-user-cache user-id)
  (-> (sqlh/update :web-user)
      (sset {:reset-code (crypto.random/hex 16)})
      (where [:= :user-id user-id])
      do-execute))

(defn clear-password-reset-code [user-id]
  (db/clear-user-cache user-id)
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
