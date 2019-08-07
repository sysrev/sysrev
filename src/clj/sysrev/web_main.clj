(ns sysrev.web-main
  (:gen-class)
  (:require [sysrev.init :as init]
            [sysrev.db.core :as db]
            [sysrev.db.migration :as migration]
            [sysrev.test.browser.core :as browser]))

(defn -main [& args]
  (init/start-db)
  (migration/ensure-updated-db)
  (init/start-app)
  (browser/cleanup-browser-test-projects)
  (db/clear-query-cache)
  (doseq [x (range)]
    (Thread/sleep (* 1000 60 30))))
