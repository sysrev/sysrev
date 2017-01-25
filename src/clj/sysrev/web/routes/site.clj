(ns sysrev.web.routes.site
  (:require [compojure.core :refer :all]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer [do-query with-query-cache]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.shared.util :refer [map-values]]
            [sysrev.util :refer [in? should-never-happen-exception]]
            [sysrev.web.app :refer [wrap-permissions current-user-id]]))

;; Functions defined after defroutes form
(declare public-project-summaries)
(declare user-info)

(defroutes site-routes
  ;; Returns short public information on all projects
  (GET "/api/all-projects" request
       (public-project-summaries))

  ;; Sets the active project for the session
  (POST "/api/select-project" request
        (wrap-permissions
         request [] []
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

  (POST "/api/delete-user" request
        (wrap-permissions
         request ["admin"] []
         (let [{{:keys [verify-user-id]
                 :as body} :body} request
               user-id (current-user-id request)
               {:keys [permissions]} (user-info user-id)]
           (assert (= user-id verify-user-id) "verify-user-id mismatch")
           (when-not (in? permissions "admin")
             (throw (should-never-happen-exception)))
           (users/delete-user user-id)
           (with-meta
             {:success true}
             {:session {}})))))

(defn public-project-summaries
  "Returns a sequence of summary maps for every project."
  []
  (with-query-cache
    [:public-project-summaries]
    (let [projects
          (->> (-> (select :*)
                   (from :project)
                   do-query)
               (group-by :project-id)
               (map-values first))
          admins
          (->> (-> (select :u.user-id :u.email :m.permissions :m.project-id)
                   (from [:project-member :m])
                   (join [:web-user :u]
                         [:= :u.user-id :m.user-id])
                   do-query)
               (group-by :project-id)
               (map-values
                (fn [pmembers]
                  (->> pmembers
                       (filter #(in? (:permissions %) "admin"))
                       (mapv #(dissoc % :project-id))))))]
      (->> projects
           (map-values
            #(assoc % :admins
                    (get admins (:project-id %) [])))))))

(defn user-info [user-id & [self?]]
  (with-query-cache
    [:users user-id [:user-info self?]]
    (let [[user project-ids]
          (pvalues
           (-> (select :user-id
                       :user-uuid
                       :email
                       :verified
                       :permissions)
               (from :web-user)
               (where [:= :user-id user-id])
               do-query
               first)
           (->>
            (-> (select :project-id)
                (from :project-member)
                (where [:= :user-id user-id])
                do-query)
            (mapv :project-id)))]
      (assoc user :projects project-ids))))
