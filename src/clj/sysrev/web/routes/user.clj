(ns sysrev.web.routes.user
  (:require [compojure.core :refer :all]
            [sysrev.api :as api]))

(defroutes user-routes
  (context "/api" []
           (context "/user/:user-id" [user-id]
                    (GET "/payments-owed" []
                         (api/payments-owed-user (Integer/parseInt user-id)))
                    )))
