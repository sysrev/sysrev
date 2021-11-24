(ns sysrev.init
  (:require [clojure.tools.logging :as log]
            sysrev.logging
            sysrev.stacktrace
            [sysrev.main :as main]
            [sysrev.web.routes.site :as site]))

(defn start-app [& [postgres-overrides only-if-new]]
  (main/start! :only-if-new only-if-new
               :postgres-overrides postgres-overrides)
  (site/init-global-stats)
  true)
