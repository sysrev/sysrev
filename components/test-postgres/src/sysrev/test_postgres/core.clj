(ns sysrev.test-postgres.core
  (:require [sysrev.db.core :as db]
            [sysrev.postgres.interface :as postgres]
            [sysrev.test-postgres.fixtures :as fixtures]))

(defn wrap-embedded-postgres [f]
  (let [old-config (:config @db/active-db)]
    ;; This is hacky. It would be better to have avoided global state.
    (postgres/start-db)
    (fixtures/load-fixtures)
    (f)
    (db/set-active-db! (db/make-db-config old-config))))
