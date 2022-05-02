(ns sysrev.project-api.interface
  (:require [sysrev.project-api.core :as core]))

(def ^{:doc "A map of lacinia resolvers."}
  resolvers core/resolvers)
