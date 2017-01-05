(ns sysrev-web.notify
  (:require
   [sysrev-web.base :refer [state]]))

(defn active-notification []
  (-> @state :notifications first))

(defn schedule-notify-display []
  (when-let [{:keys [display-ms] :as entry}
             (active-notification)]
    (js/setTimeout #(do (swap! state update :notifications pop)
                        (schedule-notify-display))
                   display-ms)))

(defn notify
  "enqueue a notification"
  [message & [{:keys [class display-ms]
               :or {class "blue" display-ms 1500}
               :as options}]]
  (let [entry {:message message
               :class class
               :display-ms display-ms}
        inactive? (empty? (-> @state :notifications))]
    ;; add entry to queue
    (swap! state update :notifications #(conj % entry))
    ;; start the display scheduler if it's not running currently
    (when inactive?
      (schedule-notify-display))))
