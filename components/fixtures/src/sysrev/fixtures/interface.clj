(ns sysrev.fixtures.interface
  (:require [sysrev.fixtures.core :as fixtures]))

(defn load-fixtures! [& [db]]
  (fixtures/load-fixtures! db))
