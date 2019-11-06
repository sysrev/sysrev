(ns sysrev.init
  (:require sysrev.logging
            sysrev.stacktrace
            sysrev.all-entities
            [sysrev.db.core :as db :refer [set-active-db! make-db-config]]
            [sysrev.web.core :refer [run-web]]
            [sysrev.config.core :refer [env]]
            [sysrev.web.routes.site :as site]
            [clojure.tools.logging :as log])
  (:import [java.net BindException]))

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
    (try (run-web server-port prod? only-if-new)
         (catch BindException _
           (log/errorf "start-web: port %d already in use" server-port)))))

(defn start-app [& [postgres-overrides server-port-override only-if-new]]
  (start-db postgres-overrides only-if-new)
  (start-web server-port-override only-if-new)
  true)
