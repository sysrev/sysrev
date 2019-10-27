(ns sysrev.web.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :as c :refer [defroutes GET ANY]]
            [compojure.route :refer [not-found]]
            [ring.util.response :as r]
            [ring.middleware.defaults :as default]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [aleph.http :as aleph]
            [sysrev.config.core :refer [env]]
            [sysrev.web.session :refer [sysrev-session-store]]
            [sysrev.web.index :as index]
            [sysrev.web.routes.auth :refer [auth-routes]]
            [sysrev.web.routes.org :refer [org-routes]]
            [sysrev.web.routes.site :refer [site-routes]]
            [sysrev.web.routes.project :refer [project-routes]]
            [sysrev.web.routes.user :refer [user-routes]]
            [sysrev.web.routes.api.core :refer [api-routes wrap-web-api]]
            sysrev.web.routes.api.handlers
            [sysrev.web.app :as app]
            [sysrev.shared.util :as sutil :refer [in?]]))

;; for clj-kondo
(declare html-routes)

(defonce app-routes nil)

(defn load-app-routes []
  (alter-var-root #'app-routes (constantly (c/routes auth-routes
                                                     site-routes
                                                     project-routes
                                                     user-routes
                                                     org-routes))))

(load-app-routes)

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

(defn wrap-sysrev-html
  "Ring handler wrapper for web HTML responses"
  [handler]
  (-> handler
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session true :anti-forgery false}))))

(defn wrap-sysrev-app
  "Ring handler wrapper for web app routes"
  [handler]
  (-> handler
      app/wrap-sysrev-response
      app/wrap-add-anti-forgery-token
      (wrap-transit-response {:encoding :json, :opts {}})
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session true :anti-forgery true}))
      (wrap-transit-body {:opts {}})
      (app/wrap-log-request)))

(defn wrap-force-json-request
  "Modifies request map to set header \"Content-Type\" as
  \"application/json\" before processing request."
  [handler]
  (fn [request]
    (handler (assoc-in request [:headers "content-type"] "application/json"))))

(defn wrap-sysrev-api
  "Ring handler wrapper for JSON API (non-browser) routes"
  [handler]
  (-> handler
      wrap-web-api
      app/wrap-sysrev-response
      (wrap-json-response {:pretty true})
      app/wrap-no-cache
      (default/wrap-defaults (sysrev-config {:session false :anti-forgery false}))
      (wrap-json-body {:keywords? true})
      wrap-force-json-request))

(defn sysrev-handler
  "Root handler for web server"
  []
  (cond-> (c/routes (ANY "/web-api/*" [] (c/wrap-routes (api-routes) wrap-sysrev-api))
                    (ANY "/api/*" [] (c/wrap-routes app-routes wrap-sysrev-app))
                    (compojure.route/resources "/")
                    (GET "/sitemap.xml" []
                         (-> (r/response (index/sysrev-sitemap))
                             (r/header "Content-Type" "application/xml; charset=utf-8")))
                    (GET "*" [] (c/wrap-routes html-routes wrap-sysrev-html)))
    (in? [:dev :test] (:profile env)) (app/wrap-no-cache)))

#_
(defn blog-handler
  "Root handler for blog web server"
  []
  (cond-> (routes (ANY "/api/*" [] (c/wrap-routes blog/blog-routes wrap-sysrev-app))
                  (compojure.route/resources "/")
                  (GET "*" [] (c/wrap-routes blog/blog-html-routes wrap-sysrev-html)))
    (in? [:dev :test] (:profile env)) (app/wrap-no-cache)))

(defonce web-servers (atom {}))
(defonce web-port (atom nil))
(defonce web-server-config (atom nil))

(defn stop-web-server []
  (let [active @web-servers]
    (doseq [id (keys active)]
      (when-let [server (get active id)]
        (.close server)
        (Thread/sleep 1000)
        (swap! web-servers assoc id nil)))))

(defn run-web [& [port prod? only-if-new]]
  (let [port (or port @web-port 4041)
        config {:port port :prod? prod?}]
    (load-app-routes)
    (when-not (and only-if-new (= config @web-server-config))
      (reset! web-port port)
      (reset! web-server-config config)
      (stop-web-server)
      (reset! web-servers
              {:main (aleph/start-server (sysrev-handler) {:port port})
               #_ :blog #_ (aleph/start-server (blog-handler) {:port (inc port)})})
      (log/info (format "web server started (port %d)" port))
      #_ (log/info (format "web server started (port %d) (blog)" (inc port)))
      @web-servers)))
