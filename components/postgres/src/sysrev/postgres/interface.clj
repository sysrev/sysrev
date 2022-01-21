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


