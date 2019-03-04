(ns sysrev.web.routes.user
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [sysrev.api :as api]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defn user-authd?
  [user-id]
  (fn [request]
    (boolean (= user-id (current-user-id request)))))

(defn user-in-group?
  [user-id group-name]
  (fn [request]
    (boolean (api/user-active-in-group? user-id "public-reviewer"))))

(defn user-owns-invitation?
  [user-id invitation-id]
  (fn [request]
    (-> (api/read-user-invitations user-id)
        (filter #(= :id invitation-id))
        (comp not empty?))))

(defroutes user-routes
  (context
   "/api" []
   (context "/users/group" []
            (GET "/public-reviewer" request
                 (wrap-authorize
                  request
                  {:logged-in true}
                  (api/users-in-group "public-reviewer")))
            (GET "/public-reviewer/:user-id" [user-id :<< as-int :as request]
                 request
                 (wrap-authorize
                  request
                  {:authorize-fn (user-in-group? user-id "public-reviewer")}
                  (api/read-user-public-info user-id))))
   (GET "/user/:user-id" [user-id :<< as-int :as request]
        (wrap-authorize
         request
         {:authorize-fn (constantly true)}
         (api/read-user-public-info user-id)))
   (context
    "/user/:user-id" [user-id :<< as-int]
    (GET "/projects" request
         (wrap-authorize
          request
          {:authorize-fn (constantly true)}
          (api/user-projects user-id (= (current-user-id request) user-id))))
    (GET "/payments-owed" request
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (api/payments-owed user-id)))
    (GET "/payments-paid" request
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (api/payments-paid user-id)))
    (POST "/support-project" request
          (wrap-authorize
           request {:authorize-fn (user-authd? user-id)}
           (let [{:keys [project-id amount frequency]} (:body request)]
             (api/support-project user-id
                                  project-id
                                  amount
                                  frequency))))
    (PUT "/introduction" request
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (let [{:keys [introduction]} (:body request)]
            (api/update-user-introduction! user-id introduction))))
    (context "/groups/:group-name" [group-name]
             (GET "/active" [user-id :<< as-int group-name :as request]
                  (wrap-authorize
                   request
                   {:authorize-fn (user-authd? user-id)}
                   (api/user-group-name-active? user-id group-name)))
             (PUT "/active" request
                  (wrap-authorize
                   request {:authorize-fn (user-authd? user-id)}
                   (let [{:keys [active]} (:body request)]
                     (api/set-web-user-group! user-id group-name active)))))
    (context "/stripe" []
             (GET "/default-source" request
                  (wrap-authorize
                   request
                   {:authorize-fn (user-authd? user-id)}
                   (api/stripe-default-source user-id)))
             (POST "/payment-method" request
                   (wrap-authorize
                    request {:authorize-fn (user-authd? user-id)}
                    (let [{:keys [token]} (:body request)]
                      (api/stripe-payment-method user-id token))))
             (GET "/current-plan" request
                  (wrap-authorize
                   request {:authorize-fn (user-authd? user-id)}
                   (api/current-plan user-id)))
             (POST "/subscribe-plan" request
                   (wrap-authorize
                    request {:authorize-fn (user-authd? user-id)}
                    (let [{:keys [plan-name]} (:body request)]
                      (api/subscribe-to-plan user-id plan-name)))))
    (GET "/invitations" request
         (wrap-authorize
          request {:authorize-fn (user-authd? user-id)}
          (api/read-user-invitations user-id)))
    (GET "/invitations/projects" request
         (wrap-authorize
          request {:authorize-fn (user-authd? user-id)}
          (api/read-invitations-for-admined-projects user-id)))
    (context "/invitation" []
             (PUT "/:invitation-id" [invitation-id :<< as-int :as request]
                  (wrap-authorize
                   request {:authorize-fn (user-owns-invitation? user-id invitation-id)}
                   (let [{:keys [accepted]} (:body request)]
                     (api/update-invitation! invitation-id accepted))))
             (POST "/:invitee/:project-id" [project-id :<< as-int
                                            invitee :<< as-int
                                            :as request]
                   (wrap-authorize
                    request {:roles ["admin"]}
                    (let [{:keys [description]} (:body request)]
                      (api/create-invitation! invitee project-id user-id description)))))
    (DELETE "/email" [:as request]
            (wrap-authorize
             request {:authorize-fn (user-authd? user-id)}
             (let [{:keys [email]} (:body request)]
               (api/delete-email! user-id email))))
    (POST "/email" [:as request]
          (wrap-authorize
           request {:authorize-fn (user-authd? user-id)}
           (let [{:keys [email]} (:body request)]
             (api/create-email! user-id email))))
    (context "/email" []
             (GET "/addresses" [:as request]
                  (wrap-authorize
                   request {:authorize-fn (user-authd? user-id)}
                   (api/read-email-addresses user-id)))
             (PUT "/send-verification" [:as request]
                  (wrap-authorize
                   request {:authorize-fn (user-authd? user-id)}
                   (let [{:keys [email]} (:body request)]
                     (api/send-verification-email user-id email))))
             (GET "/verify/:code" [code :as request]
                  (wrap-authorize
                   request {:authorize-fn (user-authd? user-id)}
                   (api/verify-email! user-id code)))
             (PUT "/set-primary" [:as request]
                  (wrap-authorize
                   request {:authorize-fn (user-authd? user-id)}
                   (let [{:keys [email]} (:body request)]
                     (api/set-primary-email! user-id email))))))))
