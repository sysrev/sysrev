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
            [aleph.http :as aleph]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [sysrev.web.session :refer [sysrev-session-store]]
            [sysrev.web.index :as index]
            [sysrev.web.blog :as blog]
            [sysrev.web.routes.auth :refer [auth-routes]]
            [sysrev.web.routes.site :refer [site-routes]]
            [sysrev.web.routes.project :refer [project-routes]]
            [sysrev.web.routes.user :refer [user-routes]]
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
    project-routes
    user-routes))

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
  [handler]
  (let [config
        (-> default/site-defaults
            (assoc-in [:session :store] (sysrev-session-store)))]
    (-> handler
        wrap-sysrev-response
        wrap-add-anti-forgery-token
        (wrap-transit-response {:encoding :json, :opts {}})
        wrap-multipart-params
        wrap-no-cache
        (default/wrap-defaults config)
        (wrap-transit-body {:opts {}}))))

(defn wrap-force-json-request
  "Modifies request map to set header \"Content-Type\" as
  \"application/json\" before processing request."
  [handler]
  (fn [request]
    (handler (assoc-in request [:headers "content-type"] "application/json"))))

(defn wrap-sysrev-api
  "Ring handler wrapper for JSON API (non-browser) routes"
  [handler]
  (let [config
        (-> default/site-defaults
            ;; (assoc-in [:session :store] (sysrev-session-store))
            (assoc-in [:security :anti-forgery] false))]
    (-> handler
        wrap-web-api
        wrap-sysrev-response
        (wrap-json-response {:pretty true})
        wrap-multipart-params
        wrap-no-cache
        (default/wrap-defaults config)
        (wrap-json-body {:keywords? true})
        wrap-force-json-request)))

(defn wrap-sysrev-html
  "Ring handler wrapper for web HTML responses"
  [handler]
  (let [config
        (-> default/site-defaults
            (assoc-in [:session :store] (sysrev-session-store))
            (assoc-in [:security :anti-forgery] false))]
    (-> handler
        wrap-no-cache
        (default/wrap-defaults config))))

(defn sysrev-handler
  "Root handler for web server"
  []
  (->>
   (routes
    (ANY "/web-api/*" []
         (wrap-routes (api-routes) wrap-sysrev-api))
    (ANY "/api/*" []
         (wrap-routes app-routes wrap-sysrev-app))
    (compojure.route/resources "/")
    (GET "/sitemap.xml" [] (-> (r/response (index/sysrev-sitemap))
                               (r/header "Content-Type"
                                         "application/xml; charset=utf-8")))
    (GET "*" []
         (wrap-routes html-routes wrap-sysrev-html)))
   (#(if (= :dev (-> env :profile))
       (wrap-no-cache %)
       %))))

(defn blog-handler
  "Root handler for blog web server"
  []
  (->>
   (routes
    (ANY "/api/*" []
         (wrap-routes blog/blog-routes wrap-sysrev-app))
    (compojure.route/resources "/")
    (GET "*" []
         (wrap-routes blog/blog-html-routes wrap-sysrev-html)))
   (#(if (= :dev (-> env :profile))
       (wrap-no-cache %)
       %))))

(defonce web-servers (atom {}))
(defonce web-port (atom nil))
(defonce web-server-config (atom nil))

(defn stop-web-server []
  (let [active @web-servers]
    (doseq [id (keys active)]
      (let [server (get active id)]
        (when-not (nil? server)
          (.close server)
          (Thread/sleep 1000)
          (swap! web-servers assoc id nil))))))

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
          (reset! web-servers
                  {:main (aleph/start-server (sysrev-handler) {:port port})
                   :blog (aleph/start-server (blog-handler) {:port (inc port)})})
          (log/info (format "web server started (port %d)" port))
          (log/info (format "web server started (port %d) (blog)" (inc port)))
          @web-servers))))
