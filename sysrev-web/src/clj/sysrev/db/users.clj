(ns sysrev.db.users
  (:require [sysrev.db.core :refer
             [do-query do-execute do-transaction
              *active-project* sql-now to-sql-array]]
            [sysrev.db.labels :refer [all-user-inclusions]]
            [sysrev.predict.core :refer [latest-predict-run]]
            [sysrev.util :refer [in? map-values]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            buddy.hashers
            crypto.random)
  (:import java.util.UUID))

(defn all-users []
  (-> (select :*)
      (from :web-user)
      do-query))

(defn get-user-by-email [email]
  (-> (select :*)
      (from :web-user)
      (where [:= :email email])
      do-query
      first))

(defn get-user-by-id [user-id]
  (-> (select :*)
      (from :web-user)
      (where [:= :user-id user-id])
      do-query
      first))

(defn encrypt-password [password]
  (buddy.hashers/encrypt
   password {:algorithm :bcrypt+sha512
             :iterations 6
             :salt (crypto.random/bytes 16)}))

(defn create-user [email password &
                   {:keys [project-id user-id permissions]
                    :or {permissions ["user"]}}]
  (let [entry
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
    (-> (insert-into :web-user)
        (values [entry])
        (returning :user-id)
        do-query)))

(defn set-user-password [email new-password]
  (-> (sqlh/update :web-user)
      (sset {:pw-encrypted-buddy (encrypt-password new-password)})
      (where [:= :email email])
      do-execute))

(defn set-user-permissions
  "Change the site permissions for a user account."
  [user-id permissions]
  (-> (sqlh/update :web-user)
      (sset {:permissions (to-sql-array "text" permissions)})
      (where [:= :user-id user-id])
      (returning :user-id :permissions)
      do-query))

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
      do-execute))

;;
;; Web queries
;;

(defn get-member-summaries []
  (let [users (->> (-> (select :u.* [:m.permissions :project-permissions])
                       (from [:web-user :u])
                       (join [:project-member :m]
                             [:= :m.user-id :u.user-id])
                       (where [:= :m.project-id *active-project*])
                       do-query)
                   (group-by :user-id)
                   (map-values first))
        inclusions (all-user-inclusions true)
        in-progress
        (->> (-> (select :user-id :%count.%distinct.ac.article-id)
                 (from [:article-criteria :ac])
                 (join [:article :a] [:= :a.article-id :ac.article-id])
                 (group :user-id)
                 (where [:and
                         [:= :a.project-id *active-project*]
                         [:!= :answer nil]
                         [:= :confirm-time nil]])
                 do-query)
             (group-by :user-id)
             (map-values (comp :count first)))]
    (->> (keys users)
         (mapv (fn [user-id]
                 [user-id
                  {:user (let [user (get users user-id)]
                           {:email (:email user)
                            :site-permissions (:permissions user)
                            :project-permissions (:project-permissions user)})
                   :articles (get inclusions user-id)
                   :in-progress (if-let [count (get in-progress user-id)]
                                  count 0)}]))
         (apply concat)
         (apply hash-map))))

(defn get-user-info [user-id]
  (let [predict-run-id
        (:predict-run-id (latest-predict-run *active-project*))
        [umap labels articles]
        (pvalues
         (-> (select :user-id
                     :email
                     :verified
                     :name
                     :username
                     :admin
                     :permissions)
             (from :web-user)
             (where [:= :user-id user-id])
             do-query
             first)
         (->>
          (-> (select :ac.article-id :criteria-id :answer :confirm-time)
              (from [:article-criteria :ac])
              (join [:article :a] [:= :a.article-id :ac.article-id])
              (where [:and
                      [:= :a.project-id *active-project*]
                      [:= :ac.user-id user-id]])
              do-query)
          (map #(-> %
                    (assoc :confirmed (not (nil? (:confirm-time %))))
                    (dissoc :confirm-time))))
         (->>
          (-> (select :a.article-id
                      :a.primary-title
                      :a.secondary-title
                      :a.authors
                      :a.year
                      :a.remote-database-name
                      [:lp.val :score])
              (from [:article :a])
              (join [:label-predicts :lp] [:= :a.article-id :lp.article-id])
              (merge-join [:criteria :c] [:= :lp.criteria-id :c.criteria-id])
              (where
               [:and
                [:= :a.project-id *active-project*]
                [:exists
                 (-> (select :*)
                     (from [:article-criteria :ac])
                     (where [:and
                             [:= :ac.user-id user-id]
                             [:= :ac.article-id :a.article-id]
                             [:!= :ac.answer nil]]))]
                [:= :c.name "overall include"]
                [:= :lp.predict-run-id predict-run-id]
                [:= :lp.stage 1]])
              do-query)
          (group-by :article-id)
          (map-values first)
          (map-values #(dissoc % :abstract :urls :notes))))
        labels-map (fn [confirmed?]
                     (->> labels
                          (filter #(= (true? (:confirmed %)) confirmed?))
                          (group-by :article-id)
                          (map-values
                           #(map (fn [m]
                                   (dissoc m :article-id :confirmed))
                                 %))
                          (filter
                           (fn [[aid cs]]
                             (some (comp not nil? :answer) cs)))
                          (apply concat)
                          (apply hash-map)))
        [confirmed unconfirmed]
        (pvalues (labels-map true) (labels-map false))]
    (assoc umap
           :labels
           {:confirmed confirmed
            :unconfirmed unconfirmed}
           :articles
           articles)))
