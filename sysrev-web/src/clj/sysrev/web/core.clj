(ns sysrev.web.core
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :refer [site]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.flash :refer [wrap-flash]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [org.httpkit.server :refer [run-server]]
            [sysrev.web.ajax :as ajax :refer [wrap-json]]
            [sysrev.web.session :refer [sysrev-session-store]]
            [sysrev.web.auth :as auth]
            [sysrev.web.index :as index]
            [sysrev.db.articles :as articles]
            [sysrev.db.documents :as docs]
            [sysrev.util :refer [parse-number]]))

(defroutes app-routes
  (GET "/" [] index/index)
  (POST "/api/auth/login" request
        (auth/web-login-handler request))
  (POST "/api/auth/logout" request
        (auth/web-logout-handler request))
  (POST "/api/auth/register" request
        (auth/web-create-account-handler request))
  (GET "/api/auth/identity" request
       (auth/web-get-identity request))
  (GET "/api/criteria" [] (wrap-json (ajax/web-criteria)))
  (GET "/api/all-projects" []
       (wrap-json (ajax/web-all-projects)))
  (GET "/api/article-documents" [] (wrap-json (docs/all-article-document-paths)))
  (GET "/api/project-info" [] (wrap-json (ajax/web-project-summary)))
  (GET "/api/user-info/:user-id" request
       (let [request-user-id (ajax/get-user-id request)
             query-user-id (-> request :params :user-id Integer/parseInt)]
         (wrap-json
          (ajax/web-user-info
           query-user-id (= request-user-id query-user-id)))))
  (GET "/api/label-task/:interval" request
       (let [user-id (ajax/get-user-id request)
             interval (-> request :params :interval Integer/parseInt)
             above-score (-> request :params :above-score)
             above-score (when above-score (Double/parseDouble above-score))]
         (wrap-json (ajax/web-label-task user-id interval above-score))))
  (GET "/api/article-info/:article-id" [article-id]
       (let [article-id (Integer/parseInt article-id)]
         (wrap-json (ajax/web-article-info article-id))))
  (POST "/api/set-labels" request
        (wrap-json (ajax/web-set-labels request false)))
  (POST "/api/confirm-labels" request
        (wrap-json (ajax/web-set-labels request true)))
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
      (wrap-session {:store (sysrev-session-store)})
      (wrap-authorization sessions-instance)
      (wrap-authentication sessions-instance)
      wrap-keyword-params
      wrap-nested-params
      wrap-params
      wrap-multipart-params
      wrap-flash
      wrap-cookies))

(def reloadable-app
  (-> app
      (wrap-reload {:dirs ["src/clj"]})))

(defonce web-server (atom nil))
(defonce web-port (atom nil))

(defn stop-web-server []
  (when-not (nil? @web-server)
    (@web-server :timeout 100)
    (reset! web-server nil)))

(defn run-web [& [port prod?]]
  (let [port (or port @web-port 4041)]
    (reset! web-port port)
    (stop-web-server)
    (reset! web-server
            (run-server (if prod? app reloadable-app)
                        {:port port
                         :join? (if prod? true false)}))))

;; if web server is running, restart it when this file is reloaded
;; ---
;; disabled for now - wrap-reload seems to conflict with this by triggering the
;; server to restart while processing a request
#_
(when @web-server
  (run-web))
