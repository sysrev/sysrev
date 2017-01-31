(ns sysrev.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(defonce build-profile
  (if js/goog.DEBUG :dev :prod))

;; Contains all app state and data pulled from server
(defonce ^:dynamic work-state (atom {}))
;; Most recent state that has all data required for rendering
(defonce display-state (r/atom {}))
;; Set to `true` while state is not ready for display
(defonce display-ready (r/atom false))

(defn init-state []
  (let [s {;; State specific to current page
           :page {}
           ;; Data pulled from server
           :data {}
           ;; Independent app-wide state for popup notifications
           :notifications #queue []}]
    (reset! work-state s)
    (reset! display-state s)))

(defonce ^:dynamic read-from-work-state false)

(defn st
  "Gets a value from current app state using `get-in`. Reads from 
  `display-state` when value of `read-from-work-state` is false (default);
  reads from `work-state` when value is true.

  Typically `read-from-work-state` will be false while executing rendering code,
  and true while executing code that deals with modifying the state."
  [& ks]
  (let [s (if read-from-work-state @work-state @display-state)]
    (get-in s ks)))

(defn st-if-exists
  "Same as `st` but accepts a `not-found` argument for `get-in`."
  [ks not-found]
  (let [s (if read-from-work-state @work-state @display-state)]
    (get-in s ks not-found)))

(secretary/set-config! :prefix "")

(defonce sysrev-hostname "sysrev.us")

(defn ga
  "google analytics (function loaded from ga.js)"
  [& more]
  (when js/ga
    (when (= js/window.location.host sysrev-hostname)
      (.. (aget js/window "ga")
          (apply nil (clj->js more))))))

(defn ga-event
  "Send a Google Analytics event."
  [category action & [label]]
  (ga "set" "userId"
      (using-work-state
       (str (st :identity :user-uuid))))
  (ga "send" "event" category action label))

;; used to detect route changes and run (ga) hook
(defonce active-route (atom nil))

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x]
                 (when (secretary/locate-route x)
                   (when (not= x @active-route)
                     (ga "set" "location" (str js/window.location.origin))
                     (ga "set" "page" (str x))
                     (ga "set" "userId"
                         (using-work-state
                          (str (st :identity :user-uuid))))
                     (ga "send" "pageview"))
                   (reset! active-route x)
                   x))))

(defn history-init []
  (pushy/start! history))
