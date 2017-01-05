(ns sysrev.web-main
  (:gen-class)
  (:require [sysrev.init :as init]))

(defn -main [& args]
  (init/start-app))
