(ns sysrev.db.migration
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.stacktrace :refer [print-cause-trace]]
            [sysrev.db.project :refer
             [add-project-member set-member-permissions]]
            [sysrev.db.core :refer
             [do-query do-query-map do-execute do-transaction active-db
              to-sql-array with-debug-sql to-jsonb sql-cast]]
            [sysrev.db.users :refer
             [get-user-by-email set-user-permissions]]
            [sysrev.db.labels :refer [add-label-entry-boolean]]
            [sysrev.util :refer [parse-xml-str map-values]]
            [sysrev.import.pubmed :refer [extract-article-location-entries]]
            [clojure.data.json :as json]
            [sysrev.db.queries :as q]
            [sysrev.db.labels :as labels])
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
    (when (not= 0 (count articles))
      (println
       (str "processed locations for " (count articles) " articles")))))

(defn ensure-new-label-entries []
  (let [project-ids (->> (-> (select :project-id)
                             (from :project)
                             (order-by [:date-created :asc])
                             do-query)
                         (map :project-id))]
    (doseq [project-id project-ids]
      (when (zero? (-> (select :%count.*)
                       (from :label)
                       (where [:= :project-id project-id])
                       do-query first :count))
        (try
          (let [project-criteria
                (-> (select :*)
                    (from :criteria)
                    (where [:= :project-id project-id])
                    (order-by [:criteria-id :asc])
                    do-query)]
            (doseq [criteria project-criteria]
              (add-label-entry-boolean
               project-id (merge
                           (->> [:name :question :short-label]
                                (select-keys criteria))
                           {:inclusion-value (:is-inclusion criteria)
                            :required (:is-required criteria)}))))
          (catch Throwable e
            (println
             (str "error creating `label` entries from `criteria`: " e))
            (print-cause-trace e)))))))

(defn ensure-new-label-value-entries []
  (let [project-ids (->> (-> (select :project-id)
                             (from :project)
                             (order-by [:date-created :asc])
                             do-query)
                         (map :project-id))]
    (doseq [project-id project-ids]
      (when (zero? (-> (q/select-project-articles project-id [:%count.*])
                       (q/join-article-labels)
                       do-query first :count))
        (try
          (let [old-entries
                (-> (select :ac.* :c.name)
                    (from [:article-criteria :ac])
                    (join [:article :a]
                          [:= :a.article-id :ac.article-id])
                    (merge-join [:criteria :c]
                                [:= :c.criteria-id :ac.criteria-id])
                    (where [:= :a.project-id project-id])
                    (order-by [:ac.article-criteria-id :asc])
                    do-query)
                project-labels
                (->> (-> (q/select-label-where project-id true [:*])
                         do-query)
                     (group-by :name)
                     (map-values first))
                new-entries
                (->>
                 old-entries
                 (mapv
                  (fn [entry]
                    {:article-id (:article-id entry)
                     :label-id (->> entry :name (get project-labels) :label-id)
                     :user-id (:user-id entry)
                     :answer (some-> (:answer entry) to-jsonb)
                     :added-time (:added-time entry)
                     :updated-time (:updated-time entry)
                     :confirm-time (:confirm-time entry)
                     :imported (:imported entry)})))]
            (when (not= 0 (count new-entries))
              (println (format "inserting %d label values" (count new-entries))))
            (->>
             new-entries
             (partition-all 50)
             (mapv
              #(-> (insert-into :article-label)
                   (values %)
                   do-execute))))
          (catch Throwable e
            (println
             (str "error creating new label value entries: " e))
            (print-cause-trace e)))))))

(defn ensure-predict-label-ids []
  (do-transaction
   nil
   (doseq [{:keys [criteria-id project-id name]}
           (-> (select :criteria-id :project-id :name)
               (from :criteria)
               do-query)]
     (let [{:keys [label-id]}
           (q/query-label-by-name project-id name [:label-id])]
       (assert label-id "label entry not found")
       (let [result
             (-> (sqlh/update :label-similarity)
                 (where [:and
                         [:= :criteria-id criteria-id]
                         [:= :label-id nil]])
                 (sset {:label-id label-id})
                 do-execute)]
         (when (not= result '(0))
           (println
            (format "updated label_similarity [%d, '%s'] : %s rows changed"
                    project-id name
                    (pr-str result)))))
       (let [result
             (-> (sqlh/update :label-predicts)
                 (where [:and
                         [:= :criteria-id criteria-id]
                         [:= :label-id nil]])
                 (sset {:label-id label-id})
                 do-execute)]
         (when (not= result '(0))
           (println
            (format "updated label_predicts [%d, '%s'] : %s rows changed"
                    project-id name
                    (pr-str result)))))))))

(defn ensure-label-inclusion-values [& [force?]]
  (let [project-ids
        (-> (select :project-id)
            (from [:project :p])
            (where
             [:or
              (true? force?)
              [:not
               [:exists
                (-> (q/select-project-articles :p.project-id [:*])
                    (q/join-article-labels)
                    (merge-where
                     [:!= :al.inclusion nil]))]]])
            (do-query-map :project-id))]
    (doseq [project-id project-ids]
      (let [alabels
            (-> (q/select-project-articles
                 project-id [:al.*])
                (q/join-article-labels)
                (merge-where [:= :al.inclusion nil])
                do-query)]
        (println
         (format "updating inclusion fields for %d rows"
                 (count alabels)))
        (doall
         (->>
          alabels
          (pmap
           (fn [alabel]
             (let [inclusion (labels/label-answer-inclusion
                              (:label-id alabel) (:answer alabel))]
               (-> (sqlh/update [:article-label :al])
                   (sset {:inclusion inclusion})
                   (where
                    [:= :al.article-label-id (:article-label-id alabel)])
                   do-execute))))))))))

(defn ensure-updated-db
  "Runs everything to update database entries to latest format."
  []
  (assert (get-default-project))
  (ensure-user-member-entries)
  (ensure-user-default-project-ids)
  (ensure-entry-uuids)
  (ensure-permissions-set)
  (ensure-test-user-perms)
  (ensure-article-location-entries)
  (ensure-new-label-entries)
  (ensure-new-label-value-entries)
  (ensure-predict-label-ids)
  (ensure-label-inclusion-values))
