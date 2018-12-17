(ns sysrev.web.routes.user
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [sysrev.api :as api]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defn user-authd?
  [user-id]
  (fn [request]
    (boolean (=  user-id (current-user-id request)))))

(defroutes user-routes
  (context
   "/api" []
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
    (GET "/opt-in" request
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (api/user-opted-in? user-id)))
    (PUT "/opt-in" request
         (wrap-authorize
          request {:authorize-fn (user-authd? user-id)}
          (let [{:keys [opt-in]} (:body request)]
            (api/set-opt-in! user-id opt-in))))
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
