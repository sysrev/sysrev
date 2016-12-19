(ns sysrev.db.project
  (:require
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.core :refer
    [do-query do-execute to-sql-array sql-cast with-project-cache
     clear-project-cache clear-query-cache cached-project-ids]]
   [sysrev.db.queries :as q]
   [sysrev.util :refer [map-values in?]])
  (:import java.util.UUID))

(defn all-projects
  "Returns seq of short info on all projects, for interactive use."
  []
  (-> (select :p.project-id
              :p.name
              :p.project-uuid
              [:%count.article-id :n-articles])
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
  (clear-project-cache project-id)
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
  (clear-project-cache project-id)
  (-> (delete-from :project-member)
      (where [:and
              [:= :project-id project-id]
              [:= :user-id user-id]])
      do-execute))

(defn set-member-permissions
  "Change the permissions for a project member."
  [project-id user-id permissions]
  (clear-project-cache project-id)
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
  (clear-query-cache)
  (-> (insert-into :project)
      (values [{:name project-name
                :enabled true
                :project-uuid (UUID/randomUUID)}])
      (returning :*)
      do-query
      first))

(defn delete-project
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id]
  (clear-query-cache)
  (-> (delete-from :project)
      (where [:= :project-id project-id])
      do-execute))

(defn project-contains-public-id
  "Test if project contains an article with given `public-id` value."
  [public-id project-id]
  (-> (q/select-article-where
       project-id [:= :a.public-id (str public-id)] [:%count.*])
      do-query first :count pos?))

(defn project-article-count
  "Return number of articles in project."
  [project-id]
  (with-project-cache
    project-id [:articles :count]
    (-> (q/select-project-articles project-id [:%count.*])
        do-query first :count)))

(defn delete-project-articles
  "Delete all articles from project."
  [project-id]
  (clear-query-cache)
  (-> (delete-from :article)
      (where [:= :project-id project-id])
      do-execute))

(defn project-labels [project-id]
  (with-project-cache
    project-id [:labels :all]
    (->>
     (-> (q/select-label-where project-id true [:*])
         do-query)
     (group-by :label-id)
     (map-values first))))

(defn project-overall-label-id [project-id]
  (with-project-cache
    project-id [:labels :overall-label-id]
    (:label-id
     (q/query-label-by-name project-id "overall include" [:label-id]))))

(defn project-member [project-id user-id]
  (with-project-cache
    project-id [:users user-id :member]
    (-> (select :*)
        (from :project-member)
        (where [:and
                [:= :project-id project-id]
                [:= :user-id user-id]])
        do-query
        first)))

(defn project-member-article-labels
  "Returns a map of labels saved by `user-id` in `project-id`,
  and a map of entries for all articles referenced in the labels."
  [project-id user-id]
  (with-project-cache
    project-id [:users user-id :labels :member-labels]
    (let [predict-run-id (q/project-latest-predict-run-id project-id)
          [labels articles]
          (pvalues
           (->>
            (-> (q/select-project-article-labels
                 project-id nil
                 [:al.article-id :al.label-id :al.answer :al.confirm-time])
                (q/filter-label-user user-id)
                do-query)
            (map
             #(-> %
                  (assoc :confirmed (not (nil? (:confirm-time %))))
                  (dissoc :confirm-time))))
           (->>
            (-> (q/select-project-articles
                 project-id
                 [:a.article-id :a.primary-title :a.secondary-title :a.authors
                  :a.year :a.remote-database-name])
                (q/with-article-predict-score predict-run-id)
                (merge-where
                 [:exists
                  (q/select-user-article-labels
                   user-id :a.article-id nil [:*])])
                do-query)
            (group-by :article-id)
            (map-values first)))
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
       :articles articles})))

;; TODO - finish/use this?
(defn project-email-domains
  [project-id]
  (let [emails (->>
                (-> (select :u.email)
                    (from [:web-user :u])
                    (join [:project-member :m]
                          [:= :m.user-id :u.user-id])
                    (where [:= :m.project-id project-id])
                    do-query)
                (mapv :email))]
    emails))

(defn delete-member-labels
  "Deletes all labels saved in `project-id` by `user-id`."
  [project-id user-id]
  (assert (integer? project-id))
  (assert (integer? user-id))
  (clear-project-cache project-id)
  (-> (delete-from [:article-label :al])
      (q/filter-label-user user-id)
      (merge-where
       [:exists
        (q/select-article-where
         project-id [:= :a.article-id :al.article-id] [:*])])
      do-execute))
