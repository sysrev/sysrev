(ns sysrev.system2.interface
  (:require [com.stuartsierra.component :as component]
            [donut.system :as ds]
            [medley.core :as medley]))

(defn config-component
  "Returns a component whose instance is the same as its config,
   but with refs resolved. The instance is nil when stopped."
  [config]
  {::ds/config config,
   ::ds/start (fn [{::ds/keys [config]}] config),
   ::ds/stop (fn [_] nil)})

(defn- stuartsierra-deps->config [component]
  (->> (meta component)
       ::component/dependencies
       (medley/map-vals (comp ds/local-ref vector))))

(defn stuartsierra->ds
  "Returns a donut.system component derived from a com.stuartsierra/component map"
  [component]
  {::ds/config (-> (stuartsierra-deps->config component)
                   (assoc ::stuartsierra-component component))
   ::ds/start
   (fn [{::ds/keys [config instance]}]
     (or instance
         (-> (merge (::stuartsierra-component config) config)
             component/start)))
   ::ds/stop
   (fn [{::ds/keys [instance]}]
     (when instance
       (component/stop instance)
       nil))})
