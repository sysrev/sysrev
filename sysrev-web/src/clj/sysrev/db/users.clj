(ns sysrev.db.users
  (:require [sysrev.db.core :refer
             [do-query do-execute do-transaction sql-now mapify-by-id]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            buddy.hashers
            crypto.random))

(defn all-users []
  (-> (select :*)
      (from :web_user)
      do-query))

(defn get-user-by-email [email]
  (-> (select :*)
      (from :web_user)
      (where [:= :email email])
      do-query
      first))

(defn get-user-by-id [user-id]
  (-> (select :*)
      (from :web_user)
      (where [:= :id user-id])
      do-query
      first))

(defn encrypt-password [password]
  (buddy.hashers/encrypt
   password {:algorithm :bcrypt+sha512
             :iterations 6
             :salt (crypto.random/bytes 16)}))

(defn create-user [email password]
  (let [now (sql-now)
        encrypted-password (encrypt-password password)
        verify-code (crypto.random/hex 16)]
    (-> (insert-into :web_user)
        (values [{:email email
                  :pw_encrypted_buddy encrypted-password
                  :verify_code verify-code
                  ;; TODO: implement email verification
                  :verified true
                  :date_created now}])
        do-execute)))

(defn set-user-password [email new-password]
  (-> (sqlh/update :web_user)
      (sset {:pw_encrypted_buddy (encrypt-password new-password)})
      (where [:= :email email])
      do-execute))

(defn valid-password? [email password-attempt]
  (let [entry (get-user-by-email email)
        encrypted-password (:pw_encrypted_buddy entry)]
    (and entry
         encrypted-password
         (buddy.hashers/check password-attempt encrypted-password))))

(defn delete-user [user-id]
  (-> (delete-from :web_user)
      (where [:= :id user-id])
      do-execute))

(defn verify-user-email [verify-code]
  (-> (sqlh/update :web_user)
      (sset {:verified true})
      (where [:= :verify_code verify-code])
      do-execute))

(defn change-user-id [current-id new-id]
  (-> (sqlh/update :web_user)
      (sset {:id new-id})
      (where [:= :id current-id])
      do-execute))

(defn get-user-labels
  "Gets a list of all labels the user has stored for all articles."
  [user-id]
  (-> (select :*)
      (from :article_criteria)
      (where [:= :user_id user-id])
      do-query))

(defn get-criteria-id [name]
  (-> (select :criteria_id)
      (from :criteria)
      (where [:= :name name])
      do-query
      first
      :criteria_id))

(defn all-user-inclusions []
  (let [overall-include-id (get-criteria-id "overall include")]
    (->> (-> (select :article_id :user_id :answer)
             (from [:article_criteria :ac])
             (where [:and
                     [:= :criteria_id overall-include-id]
                     [:or
                      [:= :answer true]
                      [:= :answer false]]])
             do-query)
         (group-by :user_id)
         (mapv (fn [[user-id entries]]
                 (let [includes
                       (->> entries
                            (filter (comp true? :answer))
                            (mapv :article_id))
                       excludes
                       (->> entries
                            (filter (comp false? :answer))
                            (mapv :article_id))]
                   [user-id {:includes includes
                             :excludes excludes}])))
         (apply concat)
         (apply hash-map))))

(defn get-user-summaries []
  (let [users (mapify-by-id (all-users) :id)
        inclusions (all-user-inclusions)]
    (->> (keys users)
         (mapv (fn [user-id]
                 [user-id
                  {:user (-> (get users user-id)
                             (select-keys [:email]))
                   :articles (get inclusions user-id)}]))
         (apply concat)
         (apply hash-map))))
