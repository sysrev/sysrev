(ns sysrev.init
  (:require sysrev.logging
            sysrev.stacktrace
            sysrev.all-entities
            [sysrev.db.core :as db :refer [set-active-db! make-db-config]]
            [sysrev.cassandra :as cdb]
            [sysrev.web.core :refer [run-web]]
            [sysrev.config.core :refer [env]]
            [sysrev.web.routes.site :as site]
            [clojure.tools.logging :as log]))

(defn start-db [& [postgres-overrides only-if-new]]
  (let [db-config (make-db-config
                   (merge (:postgres env) postgres-overrides))
        db (set-active-db! db-config only-if-new)]
    (site/init-global-stats)
    db))

(defn start-web [& [server-port-override only-if-new]]
  (let [prod? (= (:profile env) :prod)
        server-port (or server-port-override
                        (-> env :server :port))]
    (run-web server-port prod? only-if-new)))

(defn start-cassandra-db []
  (when (nil? @cdb/active-session)
    (try (cdb/connect-db)
         (log/info "connected to Cassandra DB")
         (catch Throwable e
           (log/warn "unable to connect to Cassandra DB")))))

(defn start-app [& [postgres-overrides server-port-override only-if-new]]
  (start-db postgres-overrides only-if-new)
  (start-web server-port-override only-if-new)
  (start-cassandra-db)
  true)
