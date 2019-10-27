(ns sysrev.web-main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [sysrev.init :as init]
            [sysrev.db.migration :as migration]
            [sysrev.project.core :as project]))

(defn -main [& _args]
  (init/start-db)
  (migration/ensure-updated-db)
  (project/cleanup-browser-test-projects)
  (init/start-app)
  (log/info "app startup complete")
  (doseq [_x (range)]
    (Thread/sleep (* 1000 60 30))))
