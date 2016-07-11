(ns sysrev-web.routes
    (:require [sysrev-web.base :refer [state state-val state-set]]
              [sysrev-web.ajax :as ajax]
              [secretary.core :as secretary
               :include-macros true :refer-macros [defroute]]))

(defroute "/" {}
  (ajax/pull-initial-data)
  (reset! state {:home true}))

(defroute "/user" {}
  (ajax/pull-initial-data)
  (println "got /user route")
  (reset! state {:user true}))
