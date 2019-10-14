(ns sysrev.web.routes.org
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer [POST GET PUT DELETE defroutes context]]
            [sysrev.api :as api]
            [sysrev.group.core :as group]
            [sysrev.web.app :refer [current-user-id with-authorize]]))

(defn org-role?
  "Returns true if the current user has a group permission that matches the argument vector permission"
  [org-id permissions]
  (fn [request]
    (boolean
     (let [user-id (current-user-id request)
           group-name (group/group-id->group-name org-id)]
       (and (group/user-active-in-group? user-id group-name)
            ;; test if they have the correct permissions
            (some (set permissions) (group/user-group-permission user-id org-id)))))))

(defroutes org-routes
  (context
   "/api" []
   (GET "/orgs" request
        (with-authorize request {:logged-in true}
          (api/read-orgs (current-user-id request))))
   (POST "/org" request
         (with-authorize request {:logged-in true}
           (let [{:keys [org-name]} (:body request)]
             (api/create-org! (current-user-id request) org-name))))
   (context "/org/:org-id" [org-id :<< as-int :as request]
            (GET "/users" request
                 (with-authorize request {}
                   (-> (group/group-id->group-name org-id)
                       (api/users-in-group))))
            (POST "/user" request
                  (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                    (let [user-id (get-in request [:body :user-id])]
                      (api/set-user-group! user-id (group/group-id->group-name org-id) true))))
            (PUT "/user" request
                 (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                   (let [user-id (get-in request [:body :user-id])
                         permissions (get-in request [:body :permissions])]
                     (api/set-user-group-permissions! user-id org-id permissions))))
            (DELETE "/user" request
                    (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                      (let [user-id (get-in request [:body :user-id])]
                        (api/set-user-group! user-id (group/group-id->group-name org-id) false))))
            (POST "/project" request
                  (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                    (let [project-name (-> request :body :project-name)
                          user-id (current-user-id request)]
                      (api/create-project-for-org! project-name user-id org-id))))
            (GET "/projects" request
                 (with-authorize request {}
                   (api/group-projects org-id :private-projects?
                                       ((org-role? org-id ["owner" "admin" "member"])
                                        request))))
            (context "/stripe" []
                     (GET "/default-source" request
                          (with-authorize request
                            {:authorize-fn (org-role? org-id ["owner" "admin"])}
                            (api/org-default-stripe-source org-id)))
                     (POST "/payment-method" request
                           (with-authorize request
                             {:authorize-fn (org-role? org-id ["owner" "admin"])}
                             (let [{:keys [token]} (:body request)]
                               (api/update-org-stripe-payment-method! org-id token))))
                     (GET "/current-plan" request
                          (with-authorize request
                            {:authorize-fn (org-role? org-id ["owner" "admin" "member"])}
                            (api/group-current-plan org-id)))
                     (POST "/subscribe-plan" request
                           (with-authorize request
                             {:authorize-fn (org-role? org-id ["owner" "admin"])}
                             (let [{:keys [plan-name]} (:body request)]
                               (api/subscribe-org-to-plan org-id plan-name))))))))
