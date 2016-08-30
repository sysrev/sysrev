(ns sysrev.web.core
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :refer [site]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.flash :refer [wrap-flash]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [org.httpkit.server :refer [run-server]]
            [sysrev.web.ajax :as ajax :refer [wrap-json]]
            [sysrev.web.auth :as auth]
            [sysrev.web.index :as index]
            [sysrev.db.articles :as articles]))

(def web-filesystem-path "/var/www/sysrev-clj/")

(defroutes app-routes
  (GET "/" [] index/index)
  ;; (GET "/ajax/user/:id" [id] (wrap-json "response"))
  (POST "/api/auth/login" request
        (auth/web-login-handler request))
  (POST "/api/auth/logout" request
        (auth/web-logout-handler request))
  (POST "/api/auth/register" request
        (auth/web-create-account-handler request))
  (GET "/api/auth/identity" request
       (auth/web-get-identity request))
  (GET "/api/criteria" [] (wrap-json (ajax/web-criteria)))
  (GET "/api/all-labels" [] (wrap-json (ajax/web-all-labels)))
  (GET "/api/project-users" [] (wrap-json (ajax/web-project-users)))
  (GET "/api/ranking/:page-idx" [page-idx]
       (let [page-idx (Integer/parseInt page-idx)]
         (wrap-json (articles/get-ranked-articles page-idx))))
  
  (route/files web-filesystem-path)
  (route/not-found index/index))

(defonce sessions-instance
  (session-backend {:unauthorized-handler
                    (fn [request metadata]
                      (if (authenticated? request)
                        (-> {:authorized false}
                            wrap-json
                            (assoc :status 403))
                        (-> {:authorized false}
                            wrap-json
                            (assoc :status 401))))}))

(def app
  (-> app-routes
      (wrap-file web-filesystem-path {:allow-symlinks? true})
      (wrap-authorization sessions-instance)
      (wrap-authentication sessions-instance)
      wrap-keyword-params
      wrap-nested-params
      wrap-params
      wrap-multipart-params
      wrap-flash
      wrap-cookies
      wrap-session))

(defonce web-server (atom nil))

(defn stop-web-server []
  (when-not (nil? @web-server)
    (@web-server :timeout 100)
    (reset! web-server nil)))

(defn run-web [& [port]]
  (stop-web-server)
  (reset! web-server
          (run-server app {:port (if port port 4041)
                           :join? false})))
