(ns sysrev.web.routes.project
  (:require
   [sysrev.web.app :refer [wrap-permissions current-user-id]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction
     sql-now to-sql-array *active-project* with-project]]
   [sysrev.db.users :as users]
   [sysrev.db.project :refer
    [project-member project-criteria project-article-count]]
   [sysrev.db.articles :as articles]
   [sysrev.db.documents :as docs]
   [sysrev.db.labels :as labels]
   [sysrev.predict.core :refer [latest-predict-run]]
   [sysrev.predict.report :refer [predict-summary]]
   [sysrev.util :refer
    [should-never-happen-exception map-values integerify-map-keys]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [compojure.core :refer :all]
   [sysrev.db.project :as project]))

(declare project-info)

(defroutes project-routes
  ;; Returns full information for active project
  (GET "/api/project-info" request
       (wrap-permissions
        request ["member"]
        (project-info)))

  (POST "/api/join-project" request
        (wrap-permissions
         request []
         (let [project-id (-> request :body :project-id)
               user-id (current-user-id request)
               session (assoc (:session request)
                              :active-project project-id)]
           (assert (nil? (project/project-member project-id user-id))
                   "join-project: User is already a member of this project")
           (project/add-project-member project-id user-id)
           (users/set-user-default-project user-id project-id)
           (with-meta
             {:result {:project-id project-id}}
             {:session session}))))

  ;; Returns web file paths for all local article PDF documents
  (GET "/api/article-documents" request
       (wrap-permissions
        request ["member"]
        (docs/all-article-document-paths)))

  ;; Returns an article for user to label
  (GET "/api/label-task" request
       (wrap-permissions
        request ["member"]
        {:result
         (labels/get-user-label-task (current-user-id request))}))

  ;; Sets and optionally confirms label values for an article
  (POST "/api/set-labels" request
        (wrap-permissions
         request ["member"]
         (let [user-id (current-user-id request)
               {:keys [article-id label-values confirm] :as body}
               (-> request :body integerify-map-keys)]
           (assert (not (labels/user-article-confirmed? user-id article-id)))
           (labels/set-user-article-labels user-id article-id label-values false)
           (when confirm
             (labels/confirm-user-article-labels user-id article-id))
           {:result body})))

  (GET "/api/member-labels/:user-id" request
       (wrap-permissions
        request ["member"]
        (let [user-id (-> request :params :user-id Integer/parseInt)]
          {:result
           (project/project-member-article-labels
            *active-project* user-id)})))
  
  ;; Returns map with full information on an article
  (GET "/api/article-info/:article-id" request
       (wrap-permissions
        request ["member"]
        (let [article-id (-> request :params :article-id Integer/parseInt)]
          (let [article (articles/get-article article-id)
                [score user-labels]
                (pvalues (articles/article-predict-score article)
                         (labels/article-user-labels-map article-id))]
            {:article (assoc article :score score)
             :labels user-labels})))))

(defn project-members-info []
  (let [users (->> (-> (select :u.* [:m.permissions :project-permissions])
                       (from [:web-user :u])
                       (join [:project-member :m]
                             [:= :m.user-id :u.user-id])
                       (where [:= :m.project-id *active-project*])
                       do-query)
                   (group-by :user-id)
                   (map-values first))
        inclusions (labels/all-user-inclusions true)
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
    (->> users
         (mapv (fn [[user-id user]]
                 [user-id
                  {:permissions (:project-permissions user)
                   :articles (get inclusions user-id)
                   :in-progress (if-let [count (get in-progress user-id)]
                                  count 0)}]))
         (apply concat)
         (apply hash-map))))

(defn project-users-info []
  (->> (-> (select :u.*)
           (from [:project-member :m])
           (join [:web-user :u]
                 [:= :u.user-id :m.user-id])
           (where [:= :m.project-id *active-project*])
           do-query)
       (group-by :user-id)
       (map-values first)
       (map-values
        #(select-keys % [:user-id :user-uuid :email :verified :permissions]))))

(defn project-conflict-counts []
  (let [conflicts (labels/all-label-conflicts)
        resolved? (fn [[aid labels :as conflict]]
                    (> (count labels) 2))
        n-total (count conflicts)
        n-pending (->> conflicts (remove resolved?) count)
        n-resolved (- n-total n-pending)]
    {:total n-total
     :pending n-pending
     :resolved n-resolved}))

(defn project-label-counts []
  (let [labels (labels/all-overall-labels)
        counts (->> labels vals (map count))]
    {:any (count labels)
     :single (->> counts (filter #(= % 1)) count)
     :double (->> counts (filter #(= % 2)) count)
     :multi (->> counts (filter #(> % 2)) count)}))

(defn project-label-value-counts
  "Returns counts of [true, false, unknown] label values for each criteria.
  true/false can be counted by the labels saved by all users.
  'unknown' values are counted when the user has set a value for at least one
  label on the article."
  []
  (let [entries
        (->>
         (-> (select :ac.article-id :criteria-id :user-id :answer)
             (from [:article-criteria :ac])
             (join [:article :a] [:= :a.article-id :ac.article-id])
             (where [:and
                     [:= :a.project-id *active-project*]
                     [:!= :answer nil]
                     [:!= :confirm-time nil]])
             do-query)
         (group-by :user-id)
         (map-values
          (fn [uentries]
            (->> (group-by :article-id uentries)
                 (map-values
                  #(map-values (comp first (partial map :answer))
                               (group-by :criteria-id %)))))))
        user-articles (->> (keys entries)
                           (map
                            (fn [user-id]
                              (map (fn [article-id]
                                     [user-id article-id])
                                   (keys (get entries user-id)))))
                           (apply concat))
        criteria-ids (keys (project-criteria *active-project*))
        ua-label-value
        (fn [user-id article-id criteria-id]
          (get-in entries [user-id article-id criteria-id]))
        answer-count
        (fn [criteria-id answer]
          (->> user-articles
               (filter
                (fn [[user-id article-id]]
                  (= answer (ua-label-value
                             user-id article-id criteria-id))))
               count))]
    (->> criteria-ids
         (map
          (fn [criteria-id]
            [criteria-id
             {:true (answer-count criteria-id true)
              :false (answer-count criteria-id false)
              :unknown (answer-count criteria-id nil)}]))
         (apply concat)
         (apply hash-map))))

(defn project-info [& [project-id]]
  (let [project-id (or project-id *active-project*)]
    (with-project project-id
      (let [[predict articles labels label-values conflicts members users]
            (pvalues (predict-summary
                      (:predict-run-id (latest-predict-run project-id)))
                     (project-article-count project-id)
                     (project-label-counts)
                     (project-label-value-counts)
                     (project-conflict-counts)
                     (project-members-info)
                     (project-users-info))]
        {:project {:project-id project-id
                   :members members
                   :stats {:articles articles
                           :labels labels
                           :label-values label-values
                           :conflicts conflicts
                           :predict predict}
                   :criteria (project-criteria project-id)}
         :users users}))))
