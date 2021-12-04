(ns sysrev.notifications.listeners
  (:require [clojure.edn :as edn]
            [sysrev.db.queries :as q]
            [sysrev.notification.interface :as notification]
            [sysrev.sente :as sente]))

(defn handle-notification [{:keys [sente] :as _listener} s]
  (let [{:keys [notification-id]} (edn/read-string s)
        notification (first (q/find :notification
                               {:notification-id notification-id}
                               [:content :created :publisher-id :topic-id]))
        user-ids (if (= "system" (get-in notification [:content :type]))
                   (sente/sente-connected-users sente)
                   (notification/user-ids-for-notification notification-id))]
    (doseq [uid user-ids]
      (sente/sente-dispatch!
       sente
       uid
       [:notifications/update-notifications
        {notification-id notification}]))))

(defn handle-notification-notification-subscriber
  [{:keys [sente] :as _listener} s]
  (let [{:keys [notification-ids subscriber-id ^Long viewed]} (edn/read-string s)
        viewed (when viewed (java.util.Date. viewed))
        user-id (when viewed (notification/user-id-for-subscriber subscriber-id))]
    (when user-id
      (->> notification-ids
           (reduce #(assoc % %2 {:viewed viewed}) nil)
           (vector :notifications/update-notifications)
           (sente/sente-dispatch! sente user-id)))))

