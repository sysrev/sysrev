(ns sysrev.web.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [compojure.core :as c :refer [defroutes GET ANY POST]]
            [compojure.route :refer [not-found]]
            [ring.util.response :as r]
            [ring.middleware.defaults :as default]
            [ring.middleware.json :as rmj :refer [wrap-json-body]]
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
            sysrev.web.routes.api.srvc
            [sysrev.web.app :as app]
            [sysrev.util :as util :refer [in?]])
  (:import (java.io Closeable)
           (java.sql SQLTransientConnectionException)))

;; for clj-kondo
(declare html-routes)

(def error-404
  {:body {:error {:message "Not Found"
                  :status 404
                  :type "404"}}})

(defn app-routes []
  (c/routes auth-routes
            site-routes
            project-routes
            user-routes
            org-routes
            (ANY "/api/*" [] error-404)))

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
  [handler & {:keys [sr-context]}]
  (-> handler
      app/wrap-sr-context-request
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session true :anti-forgery false}))
      (app/wrap-dynamic-vars sr-context)
      (app/wrap-sr-context sr-context)
      wrap-exit-on-full-connection-pool))

(defn wrap-sysrev-app
  "Ring handler wrapper for web app routes"
  [handler & {:keys [sr-context]}]
  (-> handler
      (app/wrap-timeout 30000)
      app/wrap-sysrev-response
      app/wrap-sr-context-request
      app/wrap-add-anti-forgery-token
      (wrap-transit-response {:encoding :json, :opts {}})
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session true :anti-forgery true}))
      (wrap-transit-body {:opts {}})
      app/wrap-robot-noindex
      (app/wrap-log-request)
      (app/wrap-dynamic-vars sr-context)
      (app/wrap-sr-context sr-context)
      wrap-exit-on-full-connection-pool))

(defn wrap-force-json-request
  "Modifies request map to set header \"Content-Type\" as
  \"application/json\" before processing request."
  [handler]
  (fn [request]
    (handler (assoc-in request [:headers "content-type"] "application/json"))))

(defn wrap-json-response [handler opts]
  (fn [request]
    (let [{:keys [headers] :as response} (handler request)]
      (case (get headers "Content-Type")
        (nil "application/json") (rmj/json-response response opts)
        response))))

(defn wrap-sysrev-api
  "Ring handler wrapper for JSON API (non-browser) routes"
  [handler & {:keys [sr-context]}]
  (-> handler
      wrap-web-api
      (app/wrap-timeout 30000)
      app/wrap-sysrev-response
      app/wrap-sr-context-request
      (wrap-json-response {:pretty true})
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session false :anti-forgery false}))
      (wrap-json-body {:keywords? true})
      wrap-force-json-request
      (app/wrap-dynamic-vars sr-context)
      (app/wrap-sr-context sr-context)
      wrap-exit-on-full-connection-pool))

(defn wrap-sysrev-graphql
  "Ring handler wrapper for GraphQL routes"
  [handler & {:keys [sr-context]}]
  (-> handler
      app/wrap-sr-context-request
      (app/wrap-dynamic-vars sr-context)
      (app/wrap-sr-context sr-context)
      wrap-exit-on-full-connection-pool))

(defn channel-socket-routes [{:keys [ajax-get-or-ws-handshake-fn
                                     ajax-post-fn sr-context]}]
  (-> (c/routes
       (GET "/api/chsk" request (ajax-get-or-ws-handshake-fn request))
       (POST "/api/chsk" request (ajax-post-fn request)))
      (c/wrap-routes wrap-sysrev-app :sr-context sr-context)))

(defn sysrev-handler
  "Root handler for web server"
  [{:keys [sente sr-context] :as web-server}]
  {:pre [(map? web-server) (map? sente) (map? sr-context)]}
  (let [wrap-dev-reload (fn [handler]
                          (if (= :dev (:profile env))
                            (fn [request] ((handler) request))
                            (handler)))
        api-routes (wrap-dev-reload
                    (fn []
                      (c/wrap-routes (api-routes) #(wrap-sysrev-api % :sr-context sr-context))))
        app-routes (wrap-dev-reload
                    (fn []
                      (c/wrap-routes (app-routes) #(wrap-sysrev-app % :sr-context sr-context))))
        graphql-routes (wrap-dev-reload
                        (fn []
                          (c/wrap-routes graphql-routes #(wrap-sysrev-graphql % :sr-context sr-context))))
        html-routes (wrap-dev-reload
                     (fn []
                       (c/wrap-routes html-routes #(wrap-sysrev-html % :sr-context sr-context))))]
    (cond-> (c/routes (ANY "/web-api/*" [] api-routes)
                      (if sente
                        (channel-socket-routes (assoc (:chsk sente) :sr-context sr-context))
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
