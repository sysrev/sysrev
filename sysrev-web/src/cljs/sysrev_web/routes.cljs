(ns sysrev-web.routes
  (:require [sysrev-web.base :refer [state state-val state-set]]
            [sysrev-web.ajax :as ajax]
            [secretary.core :as secretary
             :include-macros true :refer-macros [defroute]]))

(defroute home "/" []
  (swap! state assoc :page :home)
  (ajax/pull-initial-data)
  (println "home route."))

(defroute user "/user" []
  (ajax/pull-initial-data)
  (println "got /user route")
  (swap! state assoc :page :user))

(defroute login "/login" []
  (swap! state assoc :page :login))
