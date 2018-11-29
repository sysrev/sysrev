(ns sysrev.web.routes.user
  (:require [compojure.core :refer :all]
            [sysrev.api :as api]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defn user-authd?
  [user-id]
  (fn [request]
    (boolean (= (read-string user-id) (current-user-id request)))))

(defroutes user-routes
  (context
   "/api" []
   (context
    "/user/:user-id" [user-id]
    (GET "/payments-owed" request
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (api/payments-owed (Integer/parseInt user-id))))
    (GET "/payments-paid" request
         (wrap-authorize
          request
          {:authorize-fn (user-authd? user-id)}
          (api/payments-paid (Integer/parseInt user-id)))))))

