(ns sysrev.web.routes.user
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :as c :refer [context defroutes DELETE GET POST PUT]]
            [sysrev.api :as api]
            [sysrev.project.invitation :as invitation]
            [sysrev.web.app :refer [current-user-id with-authorize]]))

;; for clj-kondo
(declare user-routes)

(defn user-authd? [user-id]
  (fn [request]
    (boolean (= user-id (current-user-id request)))))

(defn user-in-group? [user-id group-name]
  (fn [_request]
    (boolean (api/user-active-in-group? user-id group-name))))

(defn user-owns-invitation? [user-id invitation-id]
  (fn [_request]
    (boolean (seq (->> (invitation/invitations-for-user user-id)
                       (filter #(= (:id %) invitation-id)))))))

(defroutes user-routes
  (context
   "/api" []
   (context
    "/users" []
    (context "/group" []
             (GET "/public-reviewer" request
                  (with-authorize request {:logged-in true}
                    (api/users-in-group "public-reviewer")))
             (GET "/public-reviewer/:user-id" [user-id :<< as-int :as request]
                  (with-authorize request
                    {:authorize-fn (user-in-group? user-id "public-reviewer")}
                    (api/read-user-public-info user-id))))
    (GET "/search" request
         (with-authorize request {:logged-in true}
           (let [{:keys [term]} (:params request)]
             (api/search-users term)))))
   (GET "/user/:user-id" [user-id :<< as-int :as request]
        (with-authorize request {}
          (api/read-user-public-info user-id)))
   (context
    "/user/:user-id" [user-id :<< as-int]
    (GET "/projects" request
         (with-authorize request {}
           (api/user-projects user-id (= (current-user-id request) user-id))))
    (GET "/orgs" request
         (with-authorize request {}
           (api/read-orgs user-id)))
    (GET "/payments-owed" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/user-payments-owed user-id)))
    (GET "/payments-paid" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/user-payments-paid user-id)))
    (POST "/support-project" request
          (with-authorize request {:authorize-fn (user-authd? user-id)}
            (let [{:keys [project-id amount frequency]} (:body request)]
              (api/support-project user-id project-id amount frequency))))
    (PUT "/introduction" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (let [{:keys [introduction]} (:body request)]
             (api/update-user-introduction! user-id introduction))))
    (GET "/profile-image" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/read-profile-image user-id)))
    (POST "/profile-image" request
          (with-authorize request {:authorize-fn (user-authd? user-id)}
            (let [{:keys [tempfile filename]} (get-in request [:params :file])]
              (api/create-profile-image! user-id tempfile filename))))
    (GET "/profile-image/meta" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/read-profile-image-meta user-id)))
    (GET "/profile-image" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/read-profile-image user-id)))
    (POST "/avatar" request
          (with-authorize request {:authorize-fn (user-authd? user-id)}
            (let [{:keys [file filename meta]} (get-in request [:params])]
              (api/create-avatar! user-id (:tempfile file) filename meta))))
    (GET "/avatar" _
         (api/read-avatar user-id))
    (context "/groups/public-reviewer" []
             (GET "/active" [user-id :<< as-int :as request]
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (api/user-in-group-name? user-id "public-reviewer")))
             (PUT "/active" request
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (let [{:keys [enabled]} (:body request)]
                      (api/set-user-group! user-id "public-reviewer" enabled)))))
    (context "/stripe" []
             (GET "/default-source" request
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (api/user-default-stripe-source user-id)))
             (POST "/payment-method" request
                   (with-authorize request {:authorize-fn (user-authd? user-id)}
                     (let [{:keys [payment_method]} (:body request)]
                       (api/update-user-stripe-payment-method! user-id payment_method))))
             (GET "/current-plan" request
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (api/user-current-plan user-id)))
             (POST "/subscribe-plan" request
                   (with-authorize request {:authorize-fn (user-authd? user-id)}
                     (let [{:keys [plan]} (:body request)]
                       (api/subscribe-user-to-plan user-id plan))))
             (GET "/available-plans" request
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (api/user-available-plans))))
    (GET "/invitations" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/read-user-invitations user-id)))
    (GET "/invitations/projects" request
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/read-invitations-for-admined-projects user-id)))
    (context "/invitation" []
             (PUT "/:invitation-id" [invitation-id :<< as-int :as request]
                  (with-authorize request
                    {:authorize-fn (user-owns-invitation? user-id invitation-id)}
                    (let [{:keys [accepted]} (:body request)]
                      (api/update-invitation! invitation-id accepted))))
             (POST "/:invitee/:project-id" [project-id :<< as-int
                                            invitee :<< as-int
                                            :as request]
                   (with-authorize request {:roles ["admin"]}
                     (let [{:keys [description]} (:body request)]
                       (api/create-invitation! invitee project-id user-id description)))))
    (DELETE "/email" [:as request]
            (with-authorize request {:authorize-fn (user-authd? user-id)}
              (let [{:keys [email]} (:body request)]
                (api/delete-user-email! user-id email))))
    (POST "/email" [:as request]
          (with-authorize request {:authorize-fn (user-authd? user-id)}
            (let [{:keys [email]} (:body request)]
              (api/create-user-email! user-id email))))
    (context "/email" []
             (GET "/addresses" [:as request]
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (api/user-email-addresses user-id)))
             (PUT "/send-verification" [:as request]
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (let [{:keys [email]} (:body request)]
                      (api/send-verification-email user-id email))))
             (PUT "/verify/:code" [code :as request]
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (api/verify-user-email! user-id code)))
             (PUT "/set-primary" [:as request]
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (let [{:keys [email]} (:body request)]
                      (api/set-user-primary-email! user-id email)))))
    (GET "/notifications" [:as request]
         (with-authorize request {:authorize-fn (user-authd? user-id)}
           (api/user-notifications user-id)))
    (context "/developer" []
             (PUT "/enable" [:as request]
                  (with-authorize request {:authorize-fn (user-authd? user-id)}
                    (let [{:keys [enabled?]} (:body request)]
                      (api/toggle-developer-account! user-id enabled?))))))))
