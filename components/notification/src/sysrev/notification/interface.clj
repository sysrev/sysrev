(ns sysrev.notification.interface
  (:require [sysrev.notification.core :as notification]))

(defn create-notification
  "Given a notification map matching the spec
  :sysrev.notification.interface.spec/create-notification-request,
  create the notification and return the notification-id."
  [create-notification-request]
  (notification/create-notification create-notification-request))

(defn notifications-for-subscriber
  "Return a seq of notifications for the given subscriber.
  This does not return unviewed :system notifications,
  because they don't have entries in notification_notification_subscriber.
  You should call unviewed-system-notifications as well to get all
  notifications for a subscriber."
  [subscriber-id & {:keys [limit where] :or {limit 50}}]
  (notification/notifications-for-subscriber
   subscriber-id
   :limit limit :where where))

(defn subscribe-to-topic
  "Subscribe the subscriber to the topic. Return the number of affected rows."
  [subscriber-id topic-id]
  (notification/subscribe-to-topic subscriber-id topic-id))

(defn subscriber-for-user
  "Return the subscriber corresponding to the given user-id."
  [user-id & {:keys [create? returning] :or {create? false returning :*}}]
  (notification/subscriber-for-user
   user-id
   :create? create? :returning returning))

(defn topic-for-name
  "Return the topic corresponding to the given unique-name."
  [unique-name & {:keys [create? returning] :or {create? false returning :*}}]
  (notification/topic-for-name
   unique-name
   :create? create? :returning returning))

(defn unsubscribe-from-topic
  "Remove the subscriber from the topic. Return the number of affected rows."
  [subscriber-id topic-id]
  (notification/unsubscribe-from-topic subscriber-id topic-id))

(defn unviewed-system-notifications
  "Return all unviewed system notifications for the given subscriber.
  If the subscriber is a user, it won't return notifications created before
  the user was created."
  [subscriber-id & {:keys [limit] :or {limit 50}}]
  (notification/unviewed-system-notifications subscriber-id :limit limit))

(defn update-notifications-consumed
  "Mark each notification-id as consumed and viewed by the subscriber at the
  time the transaction commits."
  [subscriber-id notification-ids]
  (notification/update-notifications-consumed subscriber-id notification-ids))

(defn update-notifications-viewed
  "Mark each notification-id as viewed by the subscriber at the time
  the transaction commits."
  [subscriber-id notification-ids]
  (notification/update-notifications-viewed subscriber-id notification-ids))

(defn user-id-for-subscriber
  "Return the user-id corresponding to the given subscriber-id, or
  nil if there is no such subscriber."
  [subscriber-id]
  (notification/user-id-for-subscriber subscriber-id))

(defn user-ids-for-notification
  "Return a seq of user-ids who should receive the given notification.
  This does not return all of the user-ids for :system notifications,
  because they don't all have entries in notification_notification_subscriber.
  You should call unviewed-system-notifications as well to get all
  notifications for a user."
  [notification-id]
  (notification/user-ids-for-notification notification-id))
