(ns sysrev.web.routes.org
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer [POST GET PUT DELETE defroutes context]]
            [sysrev.api :as api]
            [sysrev.db.groups :as groups]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defn user-has-org-permission?
  "Returns true if the current user has a group permission that matches the argument vector permission"
  [org-id permission]
  (fn [request]
    (boolean
     (let [user-id (current-user-id request)
           group-name (groups/group-id->group-name org-id)]
       (if (groups/user-active-in-group? user-id group-name)
         ;; test if they have the correct permissions
         (some (set permission) (groups/user-group-permission user-id org-id)))))))

(defroutes org-routes
  (context
   "/api" []
   (GET "/orgs" request
        (wrap-authorize
         request
         {:logged-in true}
         (api/read-orgs (current-user-id request))))
   (POST "/org" request
         (wrap-authorize
          request
          {:logged-in true}
          (let [{:keys [org-name]} (:body request)]
            (api/create-org! (current-user-id request) org-name))))
   (context "/org/:org-id" [org-id :<< as-int :as request]
            (GET "/users" request
                 (wrap-authorize
                  request
                  {:logged-in true}
                  (-> (groups/group-id->group-name org-id)
                      (api/users-in-group))))
            (POST "/user" request
                  (wrap-authorize
                   request
                   {:authorize-fn (user-has-org-permission? org-id ["admin" "owner"])}
                   (let [user-id (get-in request [:body :user-id])]
                     (api/add-user-to-org! user-id org-id))))
            (POST "/project" request
                  (wrap-authorize
                   request {:authorize-fn (user-has-org-permission? org-id ["admin" "owner"])}
                   (let [project-name (-> request :body :project-name)
                         user-id (current-user-id request)]
                     (api/create-project-for-org! project-name user-id org-id)))))))
