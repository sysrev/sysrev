(ns sysrev.web.routes.org
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer [context defroutes DELETE GET POST PUT]]
            [sysrev.api :as api]
            [sysrev.db.queries :as q]
            [sysrev.encryption :as enc]
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
                (or
                 ;; The group owner has all permissions
                 (= user-id (group/get-group-owner org-id))
                 ;; test if they have the correct permissions
                 (some (set permissions) (group/user-group-permission user-id org-id)))))))))

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
      (POST "/get-share-code" request
        (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
          {:share-code (group/get-share-hash org-id)}))
      (POST "/user" request
        (with-authorize request {:authorize-fn (org-role? org-id ["owner"])}
          (let [user-id (get-in request [:body :user-id])]
            (api/set-user-group! user-id (group/group-id->name org-id) true))))
      (POST "/join" {:keys [body] :as request}
        (with-authorize request {:logged-in true}
          (let [user-id (current-user-id request)]
            (if (-> body :invite-code enc/decrypt-wrapped64 :org-id
                    (= org-id))
              (api/set-user-group! user-id (group/group-id->name org-id) true)
              {:error {:status 403 :type :project
                       :message "Not authorized (project member)"}}))))
      (PUT "/user" request
        (with-authorize request {:authorize-fn (org-role? org-id ["owner"])}
          (let [user-id (get-in request [:body :user-id])
                permissions (get-in request [:body :permissions])]
            (api/set-user-group-permissions! user-id org-id permissions))))
      (DELETE "/user" request
        (with-authorize request {:authorize-fn (org-role? org-id ["owner"])}
          (let [user-id (get-in request [:body :user-id])]
            (api/set-user-group! user-id (group/group-id->name org-id) false))))
      (POST "/project" request
        (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
          (let [{:keys [project-name public-access]} (-> request :body)
                user-id (current-user-id request)]
            (api/create-project-for-org! project-name user-id org-id public-access))))
      (POST "/project/clone" {:keys [body sr-context] :as request}
        (with-authorize request {:authorize-fn (org-role? org-id ["admin" "owner"])}
          (let [user-id (current-user-id request)]
            (api/clone-project-for-org!
             sr-context
             {:src-project-id (:src-project-id body)
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
