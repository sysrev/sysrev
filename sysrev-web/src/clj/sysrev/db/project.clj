(ns sysrev.db.project
  (:require
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction sql-now to-sql-array]]
   [sysrev.predict.core :refer [latest-predict-run]]
   [sysrev.util :refer [map-values in?]])
  (:import java.util.UUID))

(defn all-projects
  "Returns seq of short info on all projects, for interactive use."
  []
  (-> (select :p.project-id :p.name [:%count.article-id :n-articles])
      (from [:project :p])
      (join [:article :a] [:= :a.project-id :p.project-id])
      (group :p.project-id)
      (order-by :p.date-created)
      do-query))

(defn add-project-member
  "Add a user to the list of members of a project."
  [project-id user-id &
   {:keys [permissions]
    :or {permissions ["member"]}}]
  (let [entry {:project-id project-id
               :user-id user-id
               :permissions (to-sql-array "text" permissions)}]
    (-> (insert-into :project-member)
        (values [entry])
        (returning :membership-id)
        do-query)))

(defn remove-project-member
  "Remove a user from a project."
  [project-id user-id]
  (-> (delete-from :project-member)
      (where [:and
              [:= :project-id project-id]
              [:= :user-id user-id]])
      do-execute))

(defn set-member-permissions
  "Change the permissions for a project member."
  [project-id user-id permissions]
  (-> (sqlh/update :project-member)
      (sset {:permissions (to-sql-array "text" permissions)})
      (where [:and
              [:= :project-id project-id]
              [:= :user-id user-id]])
      (returning :user-id :permissions)
      do-query))

(defn create-project
  "Create a new project entry."
  [project-name]
  (-> (insert-into :project)
      (values [{:name project-name
                :enabled true
                :project-uuid (UUID/randomUUID)}])
      (returning :project-id)
      do-query
      first))

(defn delete-project
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id]
  (-> (delete-from :project)
      (where [:= :project-id project-id])
      do-execute))

(defn project-contains-public-id
  "Test if project contains an article with given `public-id` value."
  [public-id project-id]
  (-> (select :%count.*)
      (from :article)
      (where [:and
              [:= :project-id project-id]
              [:= :public-id (str public-id)]])
      do-query first :count pos?))

(defn project-article-count
  "Return number of articles in project."
  [project-id]
  (-> (select :%count.*)
      (from :article)
      (where [:= :project-id project-id])
      do-query first :count))

(defn delete-project-articles
  "Delete all articles from project."
  [project-id]
  (-> (delete-from :article)
      (where [:= :project-id project-id])
      do-execute))

(defn project-criteria [project-id]
  (->>
   (-> (select :*)
       (from :criteria)
       (where [:= :project-id project-id])
       (order-by :criteria-id)
       do-query)
   (group-by :criteria-id)
   (map-values first)))

(defn project-member [project-id user-id]
  (-> (select :*)
      (from :project-member)
      (where [:and
              [:= :project-id project-id]
              [:= :user-id user-id]])
      do-query
      first))

(defn project-member-article-labels
  "Returns a map of labels saved by `user-id` in `project-id`,
  and a map of entries for all articles referenced in the labels."
  [project-id user-id]
  (let [predict-run-id
        (:predict-run-id (latest-predict-run project-id))
        [labels articles]
        (pvalues
         (->>
          (-> (select :ac.article-id :criteria-id :answer :confirm-time)
              (from [:article-criteria :ac])
              (join [:article :a] [:= :a.article-id :ac.article-id])
              (where [:and
                      [:= :a.project-id project-id]
                      [:= :ac.user-id user-id]])
              do-query)
          (map
           #(-> %
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
                [:= :a.project-id project-id]
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
    {:labels {:confirmed confirmed
              :unconfirmed unconfirmed}
     :articles articles}))
