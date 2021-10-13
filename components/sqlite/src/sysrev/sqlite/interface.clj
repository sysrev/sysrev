(ns sysrev.sqlite.interface
  (:require [sysrev.sqlite.core :as core]))

(defn execute!
  "Returns the result of `next.jdbc/execute!` on the connectable and
  the result of (`honey.sql/format` sqlmap)."
  [connectable sqlmap]
  (core/execute! connectable sqlmap))

(defn execute-one!
  "Returns the result of `next.jdbc/execute!` on the connectable and
  the result of (`honey.sql/format` sqlmap)."
  [connectable sqlmap]
  (core/execute-one! connectable sqlmap))

(defn plan
  "Returns the result of `next.jdbc/plan` on the connectable and
  the result of (`honey.sql/format` sqlmap)."
  [connectable sqlmap]
  (core/plan connectable sqlmap))

(defn sqlite
  "Returns a record implementing `com.stuartsierra.component/Lifecycle`
  which contains a `next.jdbc` :datasource for a SQLite database at
  filename. The file will be created if it does not exist."
  [filename]
  (sqlite filename))
