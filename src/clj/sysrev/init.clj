(ns sysrev.init
  (:require [clojure.tools.logging :as log]
            sysrev.logging
            sysrev.stacktrace
            [sysrev.main :as main]
            [sysrev.web.routes.site :as site]))

(defn start-app [port-override]
  (main/start! :port-override port-override)
  (site/init-global-stats)
  true)
