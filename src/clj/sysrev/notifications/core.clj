(ns sysrev.notifications.core
  (:require [sysrev.db.queries :as q]))

(defn subscriber-for-user [user-id & {:keys [create?]}]
  (or
   (first
    (q/find [:notification_subscriber :ns]
            {:user-id user-id}
            :*))
   (when create?
     (or
      (q/create :notification_subscriber
                {:user-id user-id}
                :prepare #(assoc % :on-conflict [:user-id] :do-nothing [])
                :returning :*)
      (subscriber-for-user user-id)))))

