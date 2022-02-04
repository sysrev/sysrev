(ns sysrev.lacinia-pedestal.interface
  (:require
   [sysrev.lacinia-pedestal.core :as core]))

(defn api-interceptors
  "Returns a vector of interceptors for a GraphQL API endpoint."
  [compiled-schema app-context]
  (core/api-interceptors compiled-schema app-context))

(def ^{:doc "An interceptor that logs and re-throws Throwables"} error-logging-interceptor
  core/error-logging-interceptor)

(defn graphiql-ide-handler
  "Returns a vector of interceptors for a GraphiQL IDE."
  [request]
  (core/graphiql-ide-handler request))

(defn pedestal
  "Returns a Pedestal component implementing
  `com.stuartsierra.component/Lifecycle`."
  [service-map-fn]
  (core/pedestal service-map-fn))

(defn subscription-interceptors
  "Returns a vector of interceptors for a GraphQL subscription WebSocket
  endpoint."
  [compiled-schema app-context]
  (core/subscription-interceptors compiled-schema app-context))
