(ns sysrev.init
  (:require [sysrev.db.core :refer [set-db-config!]]
            [config.core :refer [env]]))

(defn init []
  (set-db-config! (:postgres env)))
