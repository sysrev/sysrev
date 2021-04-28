(ns sysrev.notifications.listeners
  (:require [clojure.edn :as edn]
            [sysrev.db.queries :as q]
            [sysrev.notifications.core :as core]
            [sysrev.web.core :refer [sente-dispatch!]]))

(defn handle-notification [s]
  (let [{:keys [notification-id]} (edn/read-string s)
        notification (first (q/find :notification
                               {:notification-id notification-id}
                               [:content :created :publisher-id :topic-id]))
        user-ids (core/user-ids-for-notification notification-id)]
    (doseq [uid user-ids]
      (sente-dispatch! uid [:notifications/update-notifications
                            {notification-id notification}]))))

(defn handle-notification-notification-subscriber [s]
  (let [{:keys [notification-ids subscriber-id viewed]} (edn/read-string s)
        viewed (when viewed (java.util.Date. viewed))
        user-id (when viewed (core/user-id-for-subscriber subscriber-id))]
    (when user-id
      (->> notification-ids
           (reduce #(assoc % %2 {:viewed viewed}) nil)
           (vector :notifications/update-notifications)
           (sente-dispatch! user-id)))))

