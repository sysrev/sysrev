(ns sysrev.web.core
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :refer [site]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.file :refer [wrap-file]]
            [org.httpkit.server :refer [run-server]]
            [sysrev.web.ajax :as ajax :refer [wrap-json]]
            [sysrev.web.index :as index]))

(def web-filesystem-path "/var/www/sysrev/")

(defroutes app-routes
  (GET "/" [] index/index)
  (GET "/ajax/user/:id" [id] (wrap-json "response"))
  (GET "/api/criteria" [] (wrap-json (ajax/web-criteria)))
  (route/files web-filesystem-path)
  (route/not-found index/index))

(def app
  (-> app-routes
      (wrap-file web-filesystem-path {:allow-symlinks? true})
      (wrap-params)
      (wrap-session)))

(defonce web-server (atom nil))

(defn stop-web-server []
  (when-not (nil? @web-server)
    (@web-server :timeout 100)
    (reset! web-server nil)))

(defn run-web [& [port]]
  (stop-web-server)
  (reset! web-server
          (run-server (site app) {:port (if port port 4041)
                                  :join? false})))
