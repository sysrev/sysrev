(ns sysrev.base
  (:require [pushy.core :as pushy]
            [secretary.core :as secretary]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch]])
  (:require-macros [secretary.core :refer [defroute]]))

(defonce sysrev-hostname "sysrev.com")

(def debug?
  ^boolean js/goog.DEBUG)

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
  (let [user-uuid (subscribe [:user/uuid])]
    (ga "set" "userId" (str @user-uuid))
    (ga "send" "event" category action label)))

(secretary/set-config! :prefix "")

(defonce active-route (atom nil))

(defonce history
  (pushy/pushy
   secretary/dispatch!
   (fn [x]
     (if (secretary/locate-route x)
       (do (when (not= x @active-route)
             (when-let [user-uuid (subscribe [:user/uuid])]
               (ga "set" "location" (str js/window.location.origin))
               (ga "set" "page" (str x))
               (ga "set" "userId" (str @user-uuid))
               (ga "send" "pageview")))
           (reset! active-route x)
           x)
       (do (pushy/set-token! history "/")
           nil)))))

(def default-db {})
