(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]]))

;; Contains all app state and data pulled from server
(defonce ^:dynamic state (r/atom {}))

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
                         (-> @state :identity :user-uuid str))
                     (ga "send" "pageview"))
                   (reset! active-route x)
                   x))))

(defn history-init []
  (pushy/start! history))


