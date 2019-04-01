(ns sysrev.web.routes.organization
  (:require [compojure.core :refer [POST GET PUT DELETE defroutes context]]
            [sysrev.api :as api]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defroutes organization-routes
  (context
   "/api" []
   (GET "/organizations" request
        (wrap-authorize
         request
         {:logged-in true}
         (api/read-organizations (current-user-id request))))
   (POST "/organization" request
         (wrap-authorize
          request
          {:logged-in true}
          (let [{:keys [organization-name]} (:body request)]
            (api/create-organization! (current-user-id request) organization-name))))))
