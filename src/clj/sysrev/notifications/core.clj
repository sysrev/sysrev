(ns sysrev.notifications.core
  (:require [sysrev.db.queries :as q]))

(defn x-for-y [table-name col-name col-value & {:keys [create?]}]
  (or
   (first
    (q/find table-name
            {col-name col-value}
            :*))
   (when create?
     (or
      (q/create table-name
                {col-name col-value}
                :prepare #(assoc % :on-conflict [col-name] :do-nothing [])

                :returning :*)
      (x-for-y table-name col-name col-value)))))

(def publisher-for-project
  (partial x-for-y :notification_publisher :publisher-id))

(def publisher-for-user
  (partial x-for-y :notification_publisher :user-id))

(def subscriber-for-user
  (partial x-for-y :notification_subscriber :user-id))

(def topic-for-name
  (partial x-for-y :notification_topic :unique-name))
