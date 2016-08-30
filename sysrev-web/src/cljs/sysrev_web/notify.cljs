(ns sysrev-web.notify
  (:require
   [sysrev-web.base :refer [state]]))

(defn notify
  "enqueue a notification"
  [message]
  (swap! state update :notifications #(conj % message))
  (js/setTimeout #(swap! state update :notifications pop) 2000))

(defn notify-pop
  "Removes the oldest notification"
  []
  (swap! state update :notifications pop))

(defn notify-head
  "Get the oldest notification"
  []
  (-> @state :notifications first))
