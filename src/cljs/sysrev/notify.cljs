(ns sysrev.notify
  (:require [sysrev.base :refer [st work-state]])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(defn active-notification []
  (using-work-state
   (first (st :notifications))))

(defn schedule-notify-display []
  (using-work-state
   (when-let [{:keys [display-ms] :as entry}
              (active-notification)]
     (js/setTimeout #(do (swap! work-state update :notifications pop)
                         (schedule-notify-display))
                    display-ms))))

(defn notify
  "enqueue a notification"
  [message & [{:keys [class display-ms]
               :or {class "blue" display-ms 1500}
               :as options}]]
  (using-work-state
   (let [entry {:message message
                :class class
                :display-ms display-ms}
         inactive? (empty? (st :notifications))]
     ;; add entry to queue
     (swap! work-state update :notifications #(conj % entry))
     ;; start the display scheduler if it's not running currently
     (when inactive?
       (schedule-notify-display)))))
