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
                     (api/set-user-group! user-id (groups/group-id->group-name org-id) true))))
            (PUT "/user" request
                 (wrap-authorize
                  request
                  {:authorize-fn (user-has-org-permission? org-id ["admin" "owner"])}
                  (let [user-id (get-in request [:body :user-id])
                        permissions (get-in request [:body :permissions])]
                    (api/set-user-group-permissions! user-id org-id permissions))))
            (DELETE "/user" request
                    (wrap-authorize
                     request {:authorize-fn (user-has-org-permission? org-id ["admin" "owner"])}
                     (let [user-id (get-in request [:body :user-id])]
                       (api/set-user-group! user-id (groups/group-id->group-name org-id) false))))
            (POST "/project" request
                  (wrap-authorize
                   request {:authorize-fn (user-has-org-permission? org-id ["admin" "owner"])}
                   (let [project-name (-> request :body :project-name)
                         user-id (current-user-id request)]
                     (api/create-project-for-org! project-name user-id org-id))))
            (GET "/projects" request
                 (wrap-authorize
                  request {:authorize-fn (constantly true)}
                  (api/group-projects org-id :private-projects?
                                      ((user-has-org-permission? org-id ["owner" "admin" "member"])
                                       request))))
            (context "/stripe" []
                     (GET "/default-source" request
                          (wrap-authorize
                           request
                           {:authorize-fn (user-has-org-permission? org-id ["owner" "admin"])}
                           (api/org-stripe-default-source org-id)))
                     (POST "/payment-method" request
                           (wrap-authorize
                            request
                            {:authorize-fn (user-has-org-permission? org-id ["owner" "admin"])}
                            (let [{:keys [token]} (:body request)]
                              (api/update-org-stripe-payment-method! org-id token))))
                     (GET "/current-plan" request
                          (wrap-authorize
                           request {:authorize-fn (user-has-org-permission? org-id ["owner" "admin" "member"])}
                           (api/current-group-plan org-id)))
                     (POST "/subscribe-plan" request
                           (wrap-authorize
                            request {:authorize-fn (user-has-org-permission? org-id ["owner" "admin"])}
                            (let [{:keys [plan-name]} (:body request)
                                  user-id (current-user-id request)]
                              (api/subscribe-org-to-plan org-id plan-name))))))))
