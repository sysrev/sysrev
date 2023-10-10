(ns sysrev.postgres.interface
  (:require [sysrev.postgres.core :as core]))

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

(defmacro retry-serial
  "Retry the body on serialization errors from postgres.

   retry-opts are passed to `sysrev.util-lite.interface/retry`
   The default is 4 retries with a 100 ms starting interval."
  [retry-opts & body]
  `(core/retry-serial ~retry-opts ~@body))

(defn serialization-error?
  "Returns true if e is caused by a postgres serialization error.
   See https://wiki.postgresql.org/wiki/SSI

   The transaction can often be successfully retried when these errors occur."
  [^Throwable e]
  (core/serialization-error? e))

(defmacro with-tx
  "Either use an existing transaction in the context, or create a new
   transaction and add it to the context.

   The context can be any map, but is usually an sr-context map,
   a donut.system instance, or a lacinia context map.

   Keys used in the context:
   :datasource Postgres JDBC datasource
   :jdbc-opts Extra options passed to [[next.jdbc/with-transaction]]
     Default: {:isolation :serializable}
   :tx Postgres JDBC transaction

   Usage:

   ```
   (pg/with-tx [instance instance]
    (->> {:update :job
          :set {:status \"failed\"}
          :where [:= :id job-id]}
         (pg/execute-one! instance)))
   ```"
  [[name-sym context] & body]
  `(core/with-tx [~name-sym ~context]
     ~@body))

(defmacro with-read-tx
  "Same as [[with-tx]] with [:jdbc-opts :read-only] set to true.

   Usage:

   ```
   (pg/with-read-tx [instance instance]
    (->> {:select :* :from :job}
         (pg/execute-one! instance)))
   ```"
  [[name-sym context] & body]
  `(core/with-tx [~name-sym ~context]
     ~@body))
