(ns sysrev.web.core
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer :all]
            [compojure.route :refer [not-found]]
            [ring.util.response :as r]
            [ring.middleware.defaults :as default]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.transit :refer
             [wrap-transit-response wrap-transit-body]]
            [org.httpkit.server :refer [run-server]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [sysrev.web.session :refer [sysrev-session-store]]
            [sysrev.web.index :as index]
            [sysrev.web.routes.auth :refer [auth-routes]]
            [sysrev.web.routes.site :refer [site-routes]]
            [sysrev.web.routes.project :refer [project-routes]]
            [sysrev.web.routes.api.core :refer
             [api-routes wrap-web-api]]
            sysrev.web.routes.api.handlers
            [sysrev.web.app :refer
             [wrap-no-cache wrap-add-anti-forgery-token
              wrap-sysrev-response not-found-response]]
            [sysrev.config.core :refer [env]]))

(declare app-routes)

(defn load-app-routes []
  (defroutes app-routes
    auth-routes
    site-routes
    project-routes))

(load-app-routes)

(defroutes html-routes
  (GET "*" {:keys [uri] :as request}
       (if (some-> uri (str/split #"/") last (str/index-of \.))
         ;; Fail if request appears to be for a static file
         (not-found-response request)
         ;; Otherwise serve index.html
         (index/index request)))
  (not-found (not-found-response nil)))

(defn wrap-sysrev-app
  "Ring handler wrapper for web app routes"
  [handler & [reloadable?]]
  (let [config
        (-> default/site-defaults
            (assoc-in [:session :store] (sysrev-session-store)))]
    (-> handler
        wrap-sysrev-response
        wrap-add-anti-forgery-token
        (wrap-transit-response {:encoding :json, :opts {}})
        wrap-multipart-params
        (#(if reloadable?
            (wrap-reload % {:dirs ["src/clj"]})
            (identity %)))
        wrap-no-cache
        (default/wrap-defaults config)
        (wrap-transit-body {:opts {}}))))

(defn wrap-sysrev-api
  "Ring handler wrapper for JSON API (non-browser) routes"
  [handler & [reloadable?]]
  (let [config
        (-> default/site-defaults
            ;; (assoc-in [:session :store] (sysrev-session-store))
            (assoc-in [:security :anti-forgery] false))]
    (-> handler
        wrap-web-api
        wrap-sysrev-response
        (wrap-json-response {:pretty true})
        wrap-multipart-params
        (#(if reloadable?
            (wrap-reload % {:dirs ["src/clj"]})
            (identity %)))
        wrap-no-cache
        (default/wrap-defaults config)
        (wrap-json-body {:keywords? true}))))

(defn wrap-sysrev-html
  "Ring handler wrapper for web HTML responses"
  [handler & [reloadable?]]
  (let [config
        (-> default/site-defaults
            (assoc-in [:session :store] (sysrev-session-store))
            (assoc-in [:security :anti-forgery] false))]
    (-> handler
        (#(if reloadable?
            (wrap-reload % {:dirs ["src/clj"]})
            (identity %)))
        wrap-no-cache
        (default/wrap-defaults config))))

(defn sysrev-handler
  "Root handler for web server"
  [& [reloadable?]]
  (->>
   (routes
    (ANY "/web-api/*" []
         (wrap-routes (api-routes) wrap-sysrev-api reloadable?))
    (ANY "/api/*" []
         (wrap-routes app-routes wrap-sysrev-app reloadable?))
    (compojure.route/resources "/")
    (GET "*" []
         (wrap-routes html-routes wrap-sysrev-html reloadable?)))
   (#(if (= :dev (-> env :profile))
       (wrap-no-cache %)
       %))))

(defonce web-server (atom nil))
(defonce web-port (atom nil))
(defonce web-server-config (atom nil))

(defn stop-web-server []
  (when-not (nil? @web-server)
    (@web-server :timeout 100)
    (reset! web-server nil)))

(defn run-web [& [port prod? only-if-new]]
  (let [port (or port @web-port 4041)
        config {:port port :prod? prod?}]
    (load-app-routes)
    (if (and only-if-new
             (= config @web-server-config))
      nil
      (do (reset! web-port port)
          (reset! web-server-config config)
          (stop-web-server)
          (reset! web-server
                  (run-server (sysrev-handler (if prod? false true))
                              {:port port
                               :join? (if prod? true false)
                               :max-body 209715200}))
          (log/info (format "web server started (port %s)" port))
          @web-server))))

;; if web server is running, restart it when this file is reloaded
;; ---
;; disabled for now - wrap-reload seems to conflict with this by triggering the
;; server to restart while processing a request
#_
(when @web-server
  (run-web))
