(ns sysrev.lacinia.interface
  (:require
   [sysrev.lacinia.core :as core]))

(defn current-selection-names
  "Returns the set of selected fields at the current place in the query.

  For the query `{item(id:1) {id}}`
  Returns `#{:id}` in the item resolver

  For the `{item(id:1) {tags{id name}}}`
  Returns `#{:tags}` in the item resolver
  Returns `#{:id :name}` in the tags resolver"
  [context]
  (core/current-selection-names context))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn denamespace-keys
  "Removes namespaces from keywords and symbols in the map(s).

  Used to turn `next.jdbc` query results into a lacinia resolver result."
  [map-or-seq]
  (core/denamespace-keys map-or-seq))

(defn execute-one!
  "General SQL execution function that returns just the first row of a result.

  Builds a query with (`honey.sql/format` sqlmap) and executes it with
  `next.jdbc/execute-one!`."
  [context sqlmap]
  (core/execute-one! context sqlmap))

(defn invert
  "Returns the inversion of the map `m`, with values swapped for keys.
   
   If multiple values in `m`m map to the same key, an arbitrary value is
   retained and the others are dropped."
  [m]
  (core/invert m))

(defn ^Long parse-int-id
  "Returns parsed Long value if the string represents a normalized integer
  value (one that begins with a digit 1-9, not a zero or +/-).
  Returns nil otherwise."
  [^String id]
  (core/parse-int-id id))

(defn remap-keys
  "Removes namespaces from keywords and symbols in the map(s) and
  applies key-f to the keys of the map(s).

  Used to turn `next.jdbc` query results into a lacinia resolver result."
  [key-f map-or-seq]
  (core/remap-keys key-f map-or-seq))

(defn resolve-value
  "A resolver that returns the value unchanged.

  The resolver equivalent of `identity`."
  [_context _args value]
  (core/resolve-value nil nil value))

(def ^{:doc "A map of implementations of custom scalar types.

See https://graphql.org/learn/schema/#scalar-types
and https://lacinia.readthedocs.io/en/latest/custom-scalars.html"}
  scalars core/scalars)

(defmacro with-tx-context
  "Either use an existing transaction in the context, or create a new transaction
  and add it to the context."
  [[name-sym context] & body]
  `(core/with-tx-context [~name-sym ~context]
     ~@body))
