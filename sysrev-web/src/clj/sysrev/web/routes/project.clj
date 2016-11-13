(ns sysrev.web.routes.project
  (:require
   [sysrev.web.app :refer [wrap-permissions current-user-id]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction
     sql-now to-sql-array *active-project*]]
   [sysrev.db.users :as users]
   [sysrev.db.project :as project]
   [sysrev.db.articles :as articles]
   [sysrev.db.documents :as docs]
   [sysrev.db.labels :as labels]
   [sysrev.web.routes.summaries :refer
    [get-project-summaries project-summary]]
   [sysrev.util :refer
    [should-never-happen-exception map-values integerify-map-keys]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [compojure.core :refer :all]))

(defroutes project-routes
  ;; Returns short public information on all projects
  (GET "/api/all-projects" request
       (get-project-summaries))

  (POST "/api/select-project" request
        (wrap-permissions
         request []
         (let [user-id (current-user-id request)
               project-id (-> request :body :project-id)]
           (if (nil? (project/project-member project-id user-id))
             {:error
              {:status 403
               :type :member
               :message "Not authorized (project)"}}
             (let [session (assoc (:session request)
                                  :active-project project-id)]
               (users/set-user-default-project user-id project-id)
               (with-meta
                 {:result {:project-id project-id}}
                 {:session session}))))))

  ;; Returns web file paths for all local article PDF documents
  (GET "/api/article-documents" request
       (wrap-permissions
        request ["member"]
        (docs/all-article-document-paths)))

  ;; Returns full information for active project
  (GET "/api/project-info" request
       (wrap-permissions
        request ["member"]
        (project-summary)))

  ;; Returns info on user in context of active project
  (GET "/api/user-info/:user-id" request
       (wrap-permissions
        request ["member"]
        (let [query-user-id (-> request :params :user-id Integer/parseInt)]
          (users/get-user-info query-user-id))))

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
