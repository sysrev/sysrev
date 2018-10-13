(ns sysrev.web-main
  (:gen-class)
  (:require [sysrev.init :as init]
            [sysrev.db.migration :as migration]))

(defn -main [& args]
  (init/start-db)
  (migration/ensure-updated-db)
  (init/start-app)
  (doseq [x (range)]
    (Thread/sleep (* 1000 60 30))))
