(ns sysrev.db.migration
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.project :refer
             [add-project-member set-member-permissions]]
            [sysrev.db.core :refer
             [do-query do-execute to-sql-array with-debug-sql]]
            [sysrev.db.users :refer
             [get-user-by-email set-user-permissions]]
            [sysrev.util :refer [parse-xml-str]]
            [sysrev.import.pubmed :refer [extract-article-location-entries]])
  (:import java.util.UUID))

(defn- get-default-project
  "Selects a fallback project to use as a default. Intended only for dev use."
  []
  (-> (select :*)
      (from :project)
      (order-by [:project-id :asc])
      (limit 1)
      do-query
      first))

(defn ensure-user-member-entries
  "Ensures that each user account is a member of at least one project, by
  adding project-less users to `project-id` or default project."
  [& [project-id]]
  (let [project-id
        (or project-id
            (:project-id (get-default-project)))
        user-ids
        (->>
         (-> (select :u.user-id)
             (from [:web-user :u])
             (where
              [:not
               [:exists
                (-> (select :*)
                    (from [:project-member :m])
                    (where [:= :m.user-id :u.user-id]))]])
             do-query)
         (map :user-id))]
    (->> user-ids
         (mapv #(add-project-member project-id %)))))

(defn ensure-user-default-project-ids
  "Ensures that a default-project-id value is set for all users which belong to
  at least one project. ensure-user-member-entries should be run before this."
  []
  (let [user-ids
        (->>
         (-> (select :u.user-id)
             (from [:web-user :u])
             (where
              [:and
               [:= :u.default-project-id nil]
               [:exists
                (-> (select :*)
                    (from [:project-member :m])
                    (where [:= :m.user-id :u.user-id]))]])
             do-query)
         (map :user-id))]
    (doall
     (for [user-id user-ids]
       (let [project-id
             (-> (select :project-id)
                 (from [:project-member :m])
                 (where [:= :m.user-id user-id])
                 (order-by [:join-date :asc])
                 (limit 1)
                 do-query
                 first
                 :project-id)]
         (-> (sqlh/update :web-user)
             (sset {:default-project-id project-id})
             (where [:= :user-id user-id])
             do-execute))))))

(defmacro create-uuids [table id uuid]
  `(let [ids# (->> (-> (select ~id)
                       (from ~table)
                       (where [:= ~uuid nil])
                       do-query)
                   (map ~id))]
     (->> ids#
          (pmap
           #(-> (sqlh/update ~table)
                (sset {~uuid (UUID/randomUUID)})
                (where [:= ~id %])
                do-execute))
          doall)
     true))

(defn ensure-entry-uuids
  "Creates uuid values for database entries with none set."
  []
  (create-uuids :project :project-id :project-uuid)
  (create-uuids :web-user :user-id :user-uuid)
  (create-uuids :criteria :criteria-id :criteria-uuid)
  (create-uuids :article :article-id :article-uuid))

(defn ensure-permissions-set
  "Sets default permissions values for entries with null value."
  []
  (let [user-defaults (to-sql-array "text" ["user"])
        member-defaults (to-sql-array "text" ["member"])]
    (-> (sqlh/update :web-user)
        (sset {:permissions user-defaults})
        (where [:= :permissions nil])
        do-execute)
    (-> (sqlh/update :project-member)
        (sset {:permissions member-defaults})
        (where [:= :permissions nil])
        do-execute)
    nil))

(defn ensure-test-user-perms []
  (let [site-admins ["jeff.workman@gmail.com"
                     "tomluec@gmail.com"
                     "pattersonzak@gmail.com"]]
    (doseq [email site-admins]
      (when-let [user (get-user-by-email email)]
        (set-user-permissions (:user-id user) ["admin"])
        (set-member-permissions (:default-project-id user)
                                (:user-id user)
                                ["member"]))))
  (let [project-admins ["wonghuili@gmail.com"]]
    (doseq [email project-admins]
      (when-let [user (get-user-by-email email)]
        (set-user-permissions (:user-id user) ["user"])
        (set-member-permissions (:default-project-id user)
                                (:user-id user)
                                ["member" "admin" "resolve"])))))

(defn ensure-article-location-entries []
  (let [articles
        (-> (select :article-id :raw)
            (from [:article :a])
            (where
             [:and
              [:!= :raw nil]
              [:not
               [:exists
                (-> (select :*)
                    (from [:article-location :al])
                    (where [:= :al.article-id :a.article-id]))]]])
            do-query)]
    (->> articles
         (pmap
          (fn [{:keys [article-id raw]}]
            (let [entries
                  (->> (-> raw parse-xml-str :content first
                           extract-article-location-entries)
                       (mapv #(assoc % :article-id article-id)))]
              (when-not (empty? entries)
                (-> (insert-into :article-location)
                    (values entries)
                    do-execute))
              (println (str "processed #" article-id)))))
         doall)
    (println (str "processed locations for #"
                  (count articles)
                  " articles"))))

(defn ensure-updated-db
  "Runs everything to update from pre-multiproject database entries."
  []
  (assert (get-default-project))
  (ensure-user-member-entries)
  (ensure-user-default-project-ids)
  (ensure-entry-uuids)
  (ensure-permissions-set)
  (ensure-test-user-perms)
  (ensure-article-location-entries))
