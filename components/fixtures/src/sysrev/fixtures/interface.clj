(ns sysrev.fixtures.interface
  (:require [sysrev.fixtures.core :as fixtures]))

(defn load-fixtures! []
  (fixtures/load-fixtures!))

(defn wrap-fixtures
  "Wrap a test function in a fixture that creates an embedded
  postgres database and sets it as the active db in sysrev.db.core.

  Usage: (use-fixtures :each wrap-fixtures)"
  [f]
  (fixtures/wrap-fixtures f))
