(ns sysrev.web.routes.core
  (:require [compojure.core :as c]))

(defmacro setup-local-routes
  "Provides for defining a series of Compojure routes in a namespace
  with each individual route as a separate top-level form. routes
  provides a var name to hold the resulting Compojure routes
  value. define provides a function name for defining a single route
  within the namespace. finalize provides a function name that must be
  run after all definition forms to define the routes var."
  [{:keys [routes define finalize]}]
  `(do (defonce ~routes nil)
       (defonce ^:private atom-name# (atom []))
       (reset! atom-name# [])
       (defn- ~define [route#]
         (swap! atom-name# conj route#))
       (defn- ~finalize []
         (alter-var-root (var ~routes) (constantly (apply c/routes (deref atom-name#)))))))
