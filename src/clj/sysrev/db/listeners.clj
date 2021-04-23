(ns sysrev.db.listeners
  (:require [clojure.core.async :refer [>! chan go]]
            [clojure.tools.logging :as log]
            [sysrev.db.core :refer [*conn* active-db]])
  (:import com.impossibl.postgres.api.jdbc.PGNotificationListener
           com.impossibl.postgres.jdbc.PGDataSource))

(defn pgjdbc-ng-conn []
  (let [{:keys [config]} @active-db
        {:keys [dbname host password port user]} config
        ds (PGDataSource.)]
    (doto ds
      (.setDatabaseName dbname)
      (.setPassword password)
      (.setPort port)
      (.setServerName host)
      (.setUser user))
    (.getConnection ds)))

(defn build-listener
  "Build an async listener that subscribes to a postgres channel using
  LISTEN and executes f whenever it receives a notification.

  Notifications are delivered on I/O threads. Executing long or blocking
  operations will adversely affect performance; it should be avoided in all
  cases.

  https://impossibl.github.io/pgjdbc-ng/docs/0.8.7/user-guide/#extensions-notifications"
  [f closed-f conn]
  (reify PGNotificationListener
    (notification [this process-id channel-name payload]
      (binding [*conn* conn]
        (f process-id channel-name payload)))
    (closed [this]
      (closed-f))))

(defn register-listener [channel-names f]
  (let [conn (pgjdbc-ng-conn)
        close? (atom false)]
    (binding [*conn* conn]
      (with-open [stmt (.createStatement conn)]
        (.addNotificationListener
         conn
         (build-listener
          f
          #(when-not @close?
             (log/info "postgres notification listener disconnected. Reconnecting...")
             (register-listener channel-names f))
          conn))
        (doseq [s channel-names]
          (.executeUpdate stmt (str "LISTEN " s)))))
    (fn []
      (reset! close? true)
      (log/info "Closing postgres notification listener.")
      (.close conn))))

(defn register-channels
  "Takes a map of channel names to core.async channels. Registers
  postgres listeners for each channel that send each notification to
  the matching channel.

  Returns a thunk to close the listener."
  [channel-map]
  (register-listener
   (keys channel-map)
   (fn [_process-id channel-name payload]
     (go (>! (channel-map channel-name) payload)))))

(defonce listener-channels
  {"notification_message_subscriber" (chan)})

(defonce listener-state
  (agent {:channels listener-channels
          :close-f nil}))

(defn start-listeners! []
  (send-off
   listener-state
   (fn [{:keys [channels close-f]}]
     (when close-f (close-f))
     {:channels channels
      :close-f (register-channels channels)})))
