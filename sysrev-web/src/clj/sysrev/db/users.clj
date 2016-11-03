(ns sysrev.db.users
  (:require [sysrev.db.core :refer
             [do-query do-execute do-transaction sql-now to-sql-array]]
            [sysrev.db.articles :refer
             [get-criteria-id label-confirmed-test
              get-single-labeled-articles get-conflict-articles
              random-unlabeled-article all-label-conflicts]]
            [sysrev.db.project :refer [get-default-project]]
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
  (let [entry {:email email
               :pw-encrypted-buddy (encrypt-password password)
               :verify-code (crypto.random/hex 16)
               :permissions (to-sql-array "text" permissions)
               :default-project-id
               (or project-id
                   (:project-id (get-default-project)))
               ;; TODO: implement email verification
               :verified true
               :date-created (sql-now)
               :user-uuid (UUID/randomUUID)}
        entry (if-not (nil? user-id)
                (assoc entry :user-id user-id)
                entry)]
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

(defn valid-password? [email password-attempt]
  (let [entry (get-user-by-email email)
        encrypted-password (:pw-encrypted-buddy entry)]
    (and entry
         encrypted-password
         (buddy.hashers/check password-attempt encrypted-password))))

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

(defn get-user-labels
  "Gets a list of all labels the user has stored for all articles."
  [user-id]
  (-> (select :*)
      (from :article-criteria)
      (where [:= :user-id user-id])
      do-query))

(defn all-user-inclusions [& [confirmed?]]
  (let [overall-include-id (get-criteria-id "overall include")]
    (->> (-> (select :article-id :user-id :answer)
             (from [:article-criteria :ac])
             (where [:and
                     [:= :criteria-id overall-include-id]
                     [:!= :answer nil]
                     (label-confirmed-test confirmed?)])
             do-query)
         (group-by :user-id)
         (mapv (fn [[user-id entries]]
                 (let [includes
                       (->> entries
                            (filter (comp true? :answer))
                            (mapv :article-id))
                       excludes
                       (->> entries
                            (filter (comp false? :answer))
                            (mapv :article-id))]
                   [user-id {:includes includes
                             :excludes excludes}])))
         (apply concat)
         (apply hash-map))))

(defn get-user-summaries []
  (let [users (->> (all-users)
                   (group-by :user-id)
                   (map-values first))
        inclusions (all-user-inclusions true)
        in-progress
        (->> (-> (select :user-id :%count.%distinct.article-id)
                 (from :article-criteria)
                 (group :user-id)
                 (where [:and
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
                            :site-permissions (:permissions user)})
                   :articles (get inclusions user-id)
                   :in-progress (if-let [count (get in-progress user-id)]
                                  count 0)}]))
         (apply concat)
         (apply hash-map))))

(defn get-user-label-tasks [user-id n-max & [above-score]]
  (let [project-id 1
        predict-run-id (:predict-run-id (latest-predict-run project-id))
        [conflicts pending unlabeled-article]
        (pvalues nil ;; TODO - assign conflicts to project leaders
                 #_ (get-conflict-articles
                     predict-run-id user-id n-max above-score)
                 (get-single-labeled-articles
                  predict-run-id user-id n-max above-score)
                 (random-unlabeled-article predict-run-id))]
    (cond (not= (count conflicts) 0)
          (->> conflicts
               (map #(assoc % :review-status :conflict)))
          (not= (count pending) 0)
          (->> pending
               (map #(assoc % :review-status :single)))
          :else
          (->> [unlabeled-article]
               (map #(assoc % :review-status :fresh))))))

(defn get-user-article-labels [user-id article-id]
  (->> (-> (select :criteria-id :answer)
           (from :article-criteria)
           (where [:and
                   [:= :article-id article-id]
                   [:= :user-id user-id]])
           do-query)
       (map #(do [(:criteria-id %) (:answer %)]))
       (apply concat)
       (apply hash-map)))

(defn user-article-confirmed? [user-id article-id]
  (-> (select :%count.*)
      (from :article-criteria)
      (where [:and
              [:= :user-id user-id]
              [:= :article-id article-id]
              [:!= :confirm-time nil]])
      do-query first :count pos?))

(defn set-user-article-labels [user-id article-id label-values imported?]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (do-transaction
   (let [now (sql-now)
         existing-cids
         (->> (-> (select :criteria-id)
                  (from :article-criteria)
                  (where [:and
                          [:= :article-id article-id]
                          [:= :user-id user-id]])
                  do-query)
              (map :criteria-id))
         new-cids
         (->> (keys label-values)
              (remove (in? existing-cids)))
         new-entries
         (->> new-cids (map (fn [cid]
                              {:criteria-id cid
                               :article-id article-id
                               :user-id user-id
                               :answer (get label-values cid)
                               :confirm-time (if imported? now nil)
                               :imported imported?})))
         update-futures
         (->> existing-cids
              (map (fn [cid]
                     (future
                       (-> (sqlh/update :article-criteria)
                           (sset {:answer (get label-values cid)
                                  :updated-time now
                                  :imported imported?})
                           (where [:and
                                   [:= :article-id article-id]
                                   [:= :user-id user-id]
                                   [:= :criteria-id cid]
                                   [:or imported? [:= :confirm-time nil]]])
                           do-execute)))))
         insert-future
         (future
           (when-not (empty? new-entries)
             (-> (insert-into :article-criteria)
                 (values new-entries)
                 do-execute)))]
     (doall (map deref update-futures))
     (deref insert-future)
     true)))

(defn confirm-user-article-labels
  "Mark all labels by `user-id` on `article-id` as being confirmed at current time."
  [user-id article-id]
  (assert (not (user-article-confirmed? user-id article-id)))
  (do-transaction
   (let [required (-> (select :answer)
                      (from [:article-criteria :ac])
                      (join [:criteria :c]
                            [:= :ac.criteria-id :c.criteria-id])
                      (where [:and
                              [:= :user-id user-id]
                              [:= :article-id article-id]
                              [:= :c.name "overall include"]])
                      do-query)]
     (assert (not (empty? required)))
     (assert (every? (comp not nil? :answer) required)))
   (-> (sqlh/update :article-criteria)
       (sset {:confirm-time (sql-now)})
       (where [:and
               [:= :user-id user-id]
               [:= :article-id article-id]])
       do-execute)))

(defn confirm-user-labels
  "Mark all labels by `user-id` as being confirmed at current time,
  for articles where values for the required labels are set."
  [user-id]
  (do-transaction
   (-> (sqlh/update [:article-criteria :ac1])
       (sset {:confirm-time (sql-now)})
       (where [:and
               [:= :ac1.user-id user-id]
               [:= :ac1.confirm-time nil]
               [:exists
                (-> (select :*)
                    (from [:article-criteria :ac2])
                    (join [:criteria :c] [:= :c.criteria-id :ac2.criteria-id])
                    (where [:and
                            [:= :ac1.article-id :ac2.article-id]
                            [:= :ac2.user-id user-id]
                            [:= :c.name "overall include"]
                            [:!= :ac2.answer nil]]))]])
       do-execute)))

(defn get-user-info [user-id]
  (let [project-id 1
        predict-run-id
        (:predict-run-id (latest-predict-run project-id))
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
          (-> (select :article-id :criteria-id :answer :confirm-time)
              (from [:article-criteria :ac])
              (where [:= :user-id user-id])
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
