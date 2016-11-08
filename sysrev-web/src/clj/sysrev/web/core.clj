(ns sysrev.web.core
  (:require [compojure.core :refer :all]
            [ring.util.response :as r]
            [ring.middleware.defaults :as default]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [org.httpkit.server :refer [run-server]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [sysrev.web.session :refer [sysrev-session-store]]
            [sysrev.web.app :as app]))

(defonce sessions-instance
  (atom nil))

(defn- create-sessions-instance []
  (reset!
   sessions-instance
   (session-backend
    {:unauthorized-handler
     (fn [request metadata]
       (if-not (authenticated? request)
         (app/not-authenticated-response request)
         (app/not-authorized-response request)))})))

(defn- wrap-no-cache [handler]
  #(-> (handler %)
       (r/header "Cache-Control" "no-cache, no-store")))

(defn- wrap-add-anti-forgery-token
  "Attach csrf token value to response if request did not contain it."
  [handler]
  #(let [response (handler %)]
     (let [req-csrf (get-in % [:headers "x-csrf-token"])
           csrf-match (and req-csrf (= req-csrf *anti-forgery-token*))]
       (if (and *anti-forgery-token*
                (map? (:body response))
                (not csrf-match))
         (assoc-in response [:body :csrf-token] *anti-forgery-token*)
         response))))

(defn sysrev-app [& [reloadable?]]
  (let [config
        (-> default/site-defaults
            (assoc-in [:session :store] (sysrev-session-store)))]
    (-> app/app-routes
        app/wrap-sysrev-api
        wrap-add-anti-forgery-token
        (wrap-authorization @sessions-instance)
        (wrap-authentication @sessions-instance)
        wrap-json-response
        (#(if reloadable?
            (identity %)
            (wrap-reload % {:dirs ["src/clj"]})))
        wrap-no-cache
        (default/wrap-defaults config)
        (wrap-json-body {:keywords? true}))))

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
    (create-sessions-instance)
    (reset! web-server
            (run-server (sysrev-app (if prod? false true))
                        {:port port
                         :join? (if prod? true false)}))))

;; if web server is running, restart it when this file is reloaded
;; ---
;; disabled for now - wrap-reload seems to conflict with this by triggering the
;; server to restart while processing a request
#_
(when @web-server
  (run-web))
