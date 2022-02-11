(ns sysrev.postgres.interface
  (:require
   [sysrev.postgres.core :as core]))

(defn execute!
  "General SQL execution function that returns a fully-realized result set.

  Builds a query with (`honey.sql/format` sqlmap) and executes it with
  `next.jdbc/execute!`."
  [connectable sqlmap]
  (core/execute! connectable sqlmap))

(defn execute-one!
  "General SQL execution function that returns just the first row of a result.

  Builds a query with (`honey.sql/format` sqlmap) and executes it with
  `next.jdbc/execute-one!`."
  [connectable sqlmap]
  (core/execute-one! connectable sqlmap))

(defn jsonb-pgobject
  "Returns a jsonb-type `org.postgresql.util.PGobject` representing `x`.

  `x` will be JSON-encoded by `sysrev.json.interface/write-str`."
  [x]
  (core/jsonb-pgobject x))

(defn plan
  "General SQL execution function (for working with result sets).

  Returns a reducible that, when reduced, runs the SQL and yields the result.

  Builds a query with (`honey.sql/format` sqlmap) and passes it to
  `next.jdbc/plan`."
  [connectable sqlmap]
  (core/plan connectable sqlmap))

(defn postgres
  "Return a record implementing com.stuartsierra.component/Lifecycle
  that starts and stops a connection pool to a postgres DB."
  []
  (core/postgres))

(defn recreate-db!
  "Recreate the database from a template without stopping the component.
  Suspends the connection pool and terminates connections to the DB.

  `(get-in postgres [:config :postgres :template-dbname])` must be set."
  [postgres]
  (core/recreate-db! postgres))
