(ns sysrev.notifications.listeners
  (:require [clojure.edn :as edn]
            [sysrev.db.queries :as q]
            [sysrev.notifications.core :as core]
            [sysrev.web.core :refer [sente-dispatch!]]))

(defn handle-notification-message [s]
  (let [{:keys [message-id]} (edn/read-string s)
        message (first (q/find :notification-message
                               {:message-id message-id}
                               :*))
        user-ids (core/user-ids-for-message message-id)]
    (doseq [uid user-ids]
      (sente-dispatch! uid [:notifications/new message]))))

(defn handle-notification-message-subscriber [s]
  (let [{:keys [message-id subscriber-id viewed]} (edn/read-string s)
        user-id (when viewed (core/user-id-for-subscriber subscriber-id))]
    (when user-id
      (sente-dispatch! user-id [:notifications/update-notification
                                {:message-id message-id
                                 :viewed (java.util.Date. viewed)}]))))

