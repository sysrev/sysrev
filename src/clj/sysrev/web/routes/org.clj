(ns sysrev.web.routes.org
  (:require [compojure.coercions :refer [as-int]]
            [compojure.core :refer [POST GET PUT DELETE defroutes context]]
            [sysrev.api :as api]
            [sysrev.db.groups :as groups]
            [sysrev.web.app :refer [current-user-id wrap-authorize]]))

(defroutes org-routes
  (context
   "/api" []
   (GET "/orgs" request
        (wrap-authorize
         request
         {:logged-in true}
         (api/read-orgs (current-user-id request))))
   (context "/org/:org-id" [org-id :<< as-int :as request]
            (GET "/users" request
                 (wrap-authorize
                  request
                  {:logged-in true}
                  (-> (groups/get-group-name org-id)
                      (api/users-in-group)))))
   (POST "/org" request
         (wrap-authorize
          request
          {:logged-in true}
          (let [{:keys [org-name]} (:body request)]
            (api/create-org! (current-user-id request) org-name))))))
