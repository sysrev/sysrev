(ns sysrev.contajners.interface
  (:require [sysrev.contajners.core :as core]))

(defn container-ports
  "Returns a map of {container-port host-ports}. E.g., {5432 #{49154}}."
  [name & {:as op-map}]
  (core/container-ports name op-map))

(defn stop-container!
  "Stops the container."
  [name & {:as op-map}]
  (core/stop-container! name op-map))

(defn up!
  "Attempts to pull the image, create a container, and start it.
   Will simply start the container if one already exists with the
   same name."
  [name config & {:as op-map}]
  (core/up! name config op-map))
