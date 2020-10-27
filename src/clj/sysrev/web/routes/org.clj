(ns sysrev.web.routes.org
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer [POST GET PUT DELETE defroutes context]]
            [sysrev.api :as api]
            [sysrev.db.queries :as q]
            [sysrev.group.core :as group]
            [sysrev.web.app :refer [current-user-id with-authorize]]))

;; for clj-kondo
(declare org-routes)

(defn org-role?
  "Returns true if the current user has a group permission that matches the argument vector permission"
  [org-id permissions]
  (fn [request]
    (boolean
     (let [user-id (current-user-id request)
           dev-user? (and user-id (->> (q/get-user user-id :permissions)
                                       (some #{"admin"})))
           group-name (group/group-id->name org-id)]
       (or dev-user? ; always treat sysrev dev users as having full access
           (and (group/user-active-in-group? user-id group-name)
                ;; test if they have the correct permissions
                (some (set permissions) (group/user-group-permission user-id org-id))))))))

(defroutes org-routes
  (context
   "/api" []
   (GET "/orgs" request
        (with-authorize request {:logged-in true}
          (api/read-orgs (current-user-id request))))
   (GET "/org/valid-name" request
        (with-authorize request {:logged-in true}
          (let [org-name (get-in request [:params :org-name])]
            (api/validate-org-name org-name))))
   (POST "/org" request
         (with-authorize request {:logged-in true}
           (let [{:keys [org-name]} (:body request)]
             (api/create-org! (current-user-id request) org-name))))
   (POST "/org/pro" request
         (with-authorize request {:logged-in true}
           (let [{:keys [org-name plan payment-method]} (:body request)]
             (api/create-org-pro! (current-user-id request) org-name plan payment-method))))
   (GET "/org/available-plans" request
        (with-authorize request {:logged-in true}
          (api/org-available-plans)))
   (context "/org/:org-id" [org-id :<< as-int :as _request]
            (GET "/users" request
                 (with-authorize request {}
                   (-> (group/group-id->name org-id)
                       (api/users-in-group))))
            (POST "/user" request
                  (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                    (let [user-id (get-in request [:body :user-id])]
                      (api/set-user-group! user-id (group/group-id->name org-id) true))))
            (PUT "/user" request
                 (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                   (let [user-id (get-in request [:body :user-id])
                         permissions (get-in request [:body :permissions])]
                     (api/set-user-group-permissions! user-id org-id permissions))))
            (DELETE "/user" request
                    (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                      (let [user-id (get-in request [:body :user-id])]
                        (api/set-user-group! user-id (group/group-id->name org-id) false))))
            (POST "/project" request
                  (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                    (let [{:keys [project-name public-access]} (-> request :body)
                          user-id (current-user-id request)]
                      (api/create-project-for-org! project-name user-id org-id public-access))))
            (POST "/project/clone" request
                  (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
                    (let [{:keys [src-project-id]} (:body request)
                          user-id (current-user-id request)]
                      (api/clone-project-for-org! {:src-project-id src-project-id
                                                   :user-id user-id
                                                   :org-id org-id}))))
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
                             (let [{:keys [payment_method]} (:body request)]
                               (api/update-org-stripe-payment-method! org-id payment_method))))
                     (GET "/current-plan" request
                          (with-authorize request
                            {:authorize-fn (org-role? org-id ["owner" "admin" "member"])}
                            (api/group-current-plan org-id)))
                     (POST "/subscribe-plan" request
                           (with-authorize request
                             {:authorize-fn (org-role? org-id ["owner" "admin"])}
                             (let [{:keys [plan]} (:body request)]
                               (api/subscribe-org-to-plan org-id plan))))
                     (GET "/available-plans" request
                          (with-authorize request {:authorize-fn (org-role? org-id ["owner" "admin" "member"])}
                            (api/org-available-plans)))))))

