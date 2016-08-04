(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]]))

(defonce state (r/atom {:page 0
                        :ranking-page 0
                        ;; FIFO for notifications
                        :notifications #queue []
                        :label-activity #queue []}))

(defonce server-data (r/atom {}))

(def debug true)

(defn state-val [ks]
  (get-in @state ks))

(defn state-set [ks val]
  (swap! state #(assoc-in % ks val)))

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

(enable-console-print!)

(defn debug-container [child & children]
  (fn [child & children]
    [:div {:style {:background-color "lightgrey"}}
     child
     children]))

(defn show-debug-box
  ([title obj] [debug-container [:h1 {:key "title"} title] [:pre {:key "data"} (with-out-str (pprint obj))]])
  ([obj] (show-debug-box "" obj)))

(defn debug-box [arg & args]
  (when debug (apply show-debug-box arg args)))


(secretary/set-config! :prefix "")

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x] (when (secretary/locate-route x) x))))

(defn history-init []
  (pushy/start! history))

(defn nav!
  "takes a function which returns a route to navigate to"
  [to-route-f]
  (pushy/set-token! history (to-route-f)))



(defn label-queue-head []
  (-> @state :label-activity first))

(defn label-queue-pop []
  (swap! @state update-in [:label-activity] pop))

(defn label-queue [] (:label-activity @state))
