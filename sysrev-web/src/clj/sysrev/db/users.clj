(ns sysrev.db.users
  (:require [sysrev.db.core :refer
             [do-query do-execute do-transaction sql-now scorify-article]]
            [sysrev.db.articles :refer [get-criteria-id]]
            [sysrev.util :refer [in? map-values]]
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

(defn create-user [email password & [id]]
  (let [now (sql-now)
        encrypted-password (encrypt-password password)
        verify-code (crypto.random/hex 16)
        entry {:email email
               :pw_encrypted_buddy encrypted-password
               :verify_code verify-code
               ;; TODO: implement email verification
               :verified true
               :date_created now}
        entry (if (nil? id) entry (assoc entry :id id))]
    (-> (insert-into :web_user)
        (values [entry])
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
  (assert (integer? user-id))
  (do-transaction
   (-> (delete-from :article_criteria)
       (where [:= :user_id user-id])
       do-execute)
   (-> (delete-from :web_user)
       (where [:= :id user-id])
       do-execute)
   nil))

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
  (let [users (->> (all-users)
                   (group-by :id)
                   (map-values first))
        inclusions (all-user-inclusions)]
    (->> (keys users)
         (mapv (fn [user-id]
                 [user-id
                  {:user (-> (get users user-id)
                             (select-keys [:email]))
                   :articles (get inclusions user-id)}]))
         (apply concat)
         (apply hash-map))))

(defn get-user-label-tasks [user-id n-max & [above-score]]
  (let [above-score (or above-score -1.0)]
    (->>
     (-> (select :*)
         (from [:article :a])
         (join [:article_ranking :r] [:= :a.article_id :r._1])
         (where
          [:and
           [:> :r._2 above-score]
           [:not
            [:exists
             (-> (select :*)
                 (from [:article_criteria :ac])
                 (where [:and
                         [:= :ac.user_id user-id]
                         [:= :ac.article_id :a.article_id]
                         [:or
                          [:= :ac.answer true]
                          [:= :ac.answer false]]]))]]])
         (order-by :r._2)
         (limit n-max)
         do-query)
     (mapv scorify-article))))

(defn get-user-article-labels [user-id article-id]
  (->> (-> (select :criteria_id :answer)
           (from :article_criteria)
           (where [:and
                   [:= :article_id article-id]
                   [:= :user_id user-id]])
           do-query)
       (map #(do [(:criteria_id %) (:answer %)]))
       (apply concat)
       (apply hash-map)))

(defn set-user-article-labels [user-id article-id label-values]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (do-transaction
   (let [existing-cids
         (->> (-> (select :criteria_id)
                  (from :article_criteria)
                  (where [:and
                          [:= :article_id article-id]
                          [:= :user_id user-id]])
                  do-query)
              (map :criteria_id))]
     (doseq [cid existing-cids]
       (-> (sqlh/update :article_criteria)
           (sset {:answer (get label-values cid)
                  :updated_time (sql-now)})
           (where [:and
                   [:= :article_id article-id]
                   [:= :user_id user-id]
                   [:= :criteria_id cid]])
           do-execute))
     (let [new-cids
           (->> (keys label-values)
                (remove (in? existing-cids)))
           new-entries
           (->> new-cids (map (fn [cid]
                                {:criteria_id cid
                                 :article_id article-id
                                 :user_id user-id
                                 :answer (get label-values cid)})))]
       (when-not (empty? new-entries)
         (-> (insert-into :article_criteria)
             (values new-entries)
             do-execute))))
   nil))
