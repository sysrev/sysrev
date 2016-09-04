(ns sysrev.init
  (:require [sysrev.db.core :as db]))

(defn init []
  (db/set-db-config!))
