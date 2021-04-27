(ns sysrev.notifications.listeners
  (:require [clojure.edn :as edn]
            [sysrev.db.queries :as q]
            [sysrev.notifications.core :as core]
            [sysrev.web.core :refer [sente-dispatch!]]))

(defn handle-notification [s]
  (let [{:keys [notification-id]} (edn/read-string s)
        notification (first (q/find :notification
                               {:notification-id notification-id}
                               :*))
        user-ids (core/user-ids-for-notification notification-id)]
    (doseq [uid user-ids]
      (sente-dispatch! uid [:notifications/new notification]))))

(defn handle-notification-notification-subscriber [s]
  (let [{:keys [notification-id subscriber-id viewed]} (edn/read-string s)
        user-id (when viewed (core/user-id-for-subscriber subscriber-id))]
    (when user-id
      (sente-dispatch! user-id [:notifications/update-notification
                                {:notification-id notification-id
                                 :viewed (java.util.Date. viewed)}]))))

