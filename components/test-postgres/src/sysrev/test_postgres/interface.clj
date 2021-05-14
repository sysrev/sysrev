(ns sysrev.test-postgres.interface
  (:require [sysrev.test-postgres.core :as test-postgres]))

(defn wrap-embedded-postgres
  "Wrap a test function in a fixture that creates an embedded
  postgres database and sets it as the active db in sysrev.db.core.

  Usage: (use-fixtures :each wrap-embedded-postgres)"
  [f]
  (test-postgres/wrap-embedded-postgres f))
