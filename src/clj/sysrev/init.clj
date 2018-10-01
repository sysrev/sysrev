(ns sysrev.init
  (:require [sysrev.db.core :as db :refer [set-active-db! make-db-config]]
            [sysrev.cassandra :as cdb]
            [sysrev.web.core :refer [run-web]]
            [sysrev.config.core :refer [env]]
            [clojure.tools.logging :as log])
  (:import [org.slf4j.bridge SLF4JBridgeHandler]))

(defn init-logging []
  (SLF4JBridgeHandler/removeHandlersForRootLogger)
  (SLF4JBridgeHandler/install)
  (log/info "installed SLF4JBridgeHandler")
  true)

(defonce logging-initialized
  (init-logging))

(defn start-db [& [postgres-overrides only-if-new]]
  (let [db-config (make-db-config
                   (merge (:postgres env) postgres-overrides))]
    (set-active-db! db-config only-if-new)))

(defn start-web [& [server-port-override only-if-new]]
  (let [prod? (= (:profile env) :prod)
        server-port (or server-port-override
                        (-> env :server :port))]
    (run-web server-port prod? only-if-new)))

(defn start-app [& [postgres-overrides server-port-override only-if-new]]
  (start-db postgres-overrides only-if-new)
  (start-web server-port-override only-if-new)
  (when (nil? @cdb/active-session)
    (try (cdb/connect-db)
         (log/info "connected to Cassandra DB")
         (catch Throwable e
           (log/info "unable to connect to Cassandra DB"))))
  true)
