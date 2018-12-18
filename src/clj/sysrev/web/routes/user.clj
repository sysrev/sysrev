(ns sysrev.web.routes.user
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [sysrev.api :as api]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defn user-authd?
  [user-id]
  (fn [request]
    (boolean (=  user-id (current-user-id request)))))

(defn user-publicly-viewable?
  [user-id]
  (fn [request]
    (boolean (api/user-opted-in? user-id "public-reviewer"))))

(defroutes user-routes
  (context
   "/api" []
   (GET "/users/:opt-in-type" [opt-in-type :as request]
        request
        (wrap-authorize
         request
         {:logged-in true}
         (api/read-users-with-opt-in-type opt-in-type)))
   (GET "/users/public-reviewer/:user-id" [user-id :<< as-int :as request]
        request
        (wrap-authorize
         request
         {:authorize-fn (user-publicly-viewable? user-id)}
         (api/read-user-public-info user-id)))
   (context
    "/user/:user-id" [user-id :<< as-int]
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
    (GET "/opt-in/:opt-in-type" [user-id :<< as-int opt-in-type :as request]
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (api/user-opted-in? user-id opt-in-type)))
    (PUT "/opt-in" request
         (wrap-authorize
          request {:authorize-fn (user-authd? user-id)}
          (let [{:keys [opt-in opt-in-type]} (:body request)]
            (api/set-opt-in! user-id opt-in-type opt-in))))
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
                      (api/subscribe-to-plan user-id plan-name))))))))
