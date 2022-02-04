(ns sysrev.lacinia.interface
  (:require
   [sysrev.lacinia.core :as core]))

(def ^{:doc "A map of implementations of custom scalar types.

See https://graphql.org/learn/schema/#scalar-types
and https://lacinia.readthedocs.io/en/latest/custom-scalars.html"}
  scalars core/scalars)

(defn resolve-value
  "A resolver that returns the value unchanged.

  The resolver equivalent of `identity`."
  [_context _args value]
  (core/resolve-value nil nil value))
