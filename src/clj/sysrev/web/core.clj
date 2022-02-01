(ns sysrev.web.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [compojure.core :as c :refer [defroutes GET ANY POST]]
            [compojure.route :refer [not-found]]
            [ring.util.response :as r]
            [ring.middleware.defaults :as default]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [aleph.http :as http]
            [aleph.netty]
            [sysrev.config :refer [env]]
            [sysrev.web.session :refer [sysrev-session-store]]
            [sysrev.web.index :as index]
            [sysrev.web.routes.auth :refer [auth-routes]]
            [sysrev.web.routes.org :refer [org-routes]]
            [sysrev.web.routes.site :refer [site-routes]]
            [sysrev.web.routes.project :refer [project-routes]]
            [sysrev.web.routes.user :refer [user-routes]]
            [sysrev.web.routes.api.core :refer [api-routes wrap-web-api]]
            [sysrev.web.routes.graphql :refer [graphql-routes]]
            sysrev.web.routes.api.handlers
            [sysrev.web.app :as app]
            [sysrev.util :as util :refer [in?]])
  (:import (java.io Closeable)
           (java.sql SQLTransientConnectionException)))

;; for clj-kondo
(declare html-routes)

(defn app-routes []
  (c/routes auth-routes
            site-routes
            project-routes
            user-routes
            org-routes))

(defroutes html-routes
  (GET "*" {:keys [uri] :as request}
       (if (some-> uri (str/split #"/") last (str/index-of \.))
         ;; Fail if request appears to be for a static file
         (app/not-found-response request)
         ;; Otherwise serve index.html
         (index/index request)))
  (not-found (app/not-found-response nil)))

(defn sysrev-config
  "Returns a config map for ring.defaults/wrap-defaults."
  [{:keys [session anti-forgery] :or {session false
                                      anti-forgery false}}]
  (cond-> default/site-defaults
    session        (-> (assoc-in [:session :store] (sysrev-session-store))
                       (assoc-in [:session :cookie-attrs :max-age] (* 60 60 24 365 2))
                       (cond-> (env :sysrev-hostname)
                         (assoc-in [:session :cookie-attrs :domain] (env :sysrev-hostname))))
    (not session)  (-> (assoc-in [:session :cookie-name] "ring-session-temp")
                       (assoc-in [:session :cookie-attrs :max-age] (* 60 60 24 2)))
    true           (assoc-in [:security :anti-forgery] (boolean anti-forgery))))

(defn wrap-exit-on-full-connection-pool
  "Calls System/exit when the connection pool is exhausted for too long.
  The systemd service should handle restarting sysrev."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch SQLTransientConnectionException e
        (if (re-matches #".*Connection is not available, request timed out after \d+ms.*"
                        (.getMessage e))
          (do (log/fatal e "Exiting due to full connection pool")
              (System/exit 1))
          (throw e))))))

(defn wrap-sysrev-html
  "Ring handler wrapper for web HTML responses"
  [handler & {:keys [web-server]}]
  (-> handler
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session true :anti-forgery false}))
      (app/wrap-dynamic-vars web-server)
      (app/wrap-web-server web-server)
      wrap-exit-on-full-connection-pool))

(defn wrap-sysrev-app
  "Ring handler wrapper for web app routes"
  [handler & {:keys [web-server]}]
  (-> handler
      app/wrap-sysrev-response
      app/wrap-add-anti-forgery-token
      (wrap-transit-response {:encoding :json, :opts {}})
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session true :anti-forgery true}))
      (wrap-transit-body {:opts {}})
      app/wrap-robot-noindex
      (app/wrap-log-request)
      (app/wrap-dynamic-vars web-server)
      (app/wrap-web-server web-server)
      wrap-exit-on-full-connection-pool))

(defn wrap-force-json-request
  "Modifies request map to set header \"Content-Type\" as
  \"application/json\" before processing request."
  [handler]
  (fn [request]
    (handler (assoc-in request [:headers "content-type"] "application/json"))))

(defn wrap-sysrev-api
  "Ring handler wrapper for JSON API (non-browser) routes"
  [handler & {:keys [web-server]}]
  (-> handler
      wrap-web-api
      app/wrap-sysrev-response
      (wrap-json-response {:pretty true})
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session false :anti-forgery false}))
      (wrap-json-body {:keywords? true})
      wrap-force-json-request
      (app/wrap-dynamic-vars web-server)
      (app/wrap-web-server web-server)
      wrap-exit-on-full-connection-pool))

(defn wrap-sysrev-graphql
  "Ring handler wrapper for GraphQL routes"
  [handler & {:keys [web-server]}]
  (-> handler
      (app/wrap-dynamic-vars web-server)
      (app/wrap-web-server web-server)
      wrap-exit-on-full-connection-pool))

(defn channel-socket-routes [{:keys [ajax-get-or-ws-handshake-fn
                                     ajax-post-fn
                                     web-server]}]
  (-> (c/routes
       (GET "/api/chsk" request (ajax-get-or-ws-handshake-fn request))
       (POST "/api/chsk" request (ajax-post-fn request)))
      (c/wrap-routes wrap-sysrev-app :web-server web-server)))

(defn sysrev-handler
  "Root handler for web server"
  [& [{:keys [sente] :as web-server}]]
  (assert (map? web-server))
  (let [wrap-dev-reload (fn [handler]
                          (if (= :dev (:profile env))
                            (fn [request] ((handler) request))
                            (handler)))
        api-routes (wrap-dev-reload
                    (fn []
                      (c/wrap-routes (api-routes) #(wrap-sysrev-api % :web-server web-server))))
        app-routes (wrap-dev-reload
                    (fn []
                      (c/wrap-routes (app-routes) #(wrap-sysrev-app % :web-server web-server))))
        graphql-routes (wrap-dev-reload
                        (fn []
                          (c/wrap-routes graphql-routes #(wrap-sysrev-graphql % :web-server web-server))))
        html-routes (wrap-dev-reload
                     (fn []
                       (c/wrap-routes html-routes #(wrap-sysrev-html % :web-server web-server))))]
    (cond-> (c/routes (ANY "/web-api/*" [] api-routes)
                      (if sente
                        (channel-socket-routes (assoc (:chsk sente)
                                                      :web-server web-server))
                        (constantly nil))
                      (ANY "/api/*" [] app-routes)
                      (ANY "/graphql" [] graphql-routes)
                      (compojure.route/resources "/")
                      (GET "/sitemap.xml" []
                           (-> (r/response (index/sysrev-sitemap))
                               (r/header "Content-Type" "application/xml; charset=utf-8")))
                      (GET "*" [] html-routes))
      (in? [:dev :test] (:profile env)) (app/wrap-no-cache))))

(defrecord WebServer [bound-port handler handler-f port ^Closeable server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [handler (handler-f this)
            server (http/start-server handler {:port port})]
        (assoc this
               :bound-port (aleph.netty/port server)
               :handler handler
               :server server))))
  (stop [this]
    (if-not server
      this
      (do
        (.close server)
        (aleph.netty/wait-for-close server)
        (assoc this :bound-port nil :handler nil :server nil)))))

(defn web-server [& {:keys [handler-f port]}]
  (map->WebServer {:handler-f handler-f :port port}))
