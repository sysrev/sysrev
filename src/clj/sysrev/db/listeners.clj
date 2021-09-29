(ns sysrev.db.listeners
  (:require [clojure.core.async :refer [<! >! chan go]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [medley.core :refer [map-vals]]
            [sysrev.db.core :refer [*conn*]]
            [sysrev.notifications.listeners
             :refer [handle-notification
                     handle-notification-notification-subscriber]]
            [sysrev.stacktrace :refer [print-cause-trace-custom]])
  (:import com.impossibl.postgres.api.jdbc.PGNotificationListener
           com.impossibl.postgres.jdbc.PGDataSource))

(defn- pgjdbc-ng-conn [postgres-config]
  (let [{:keys [dbname host password port user]} postgres-config
        ds (PGDataSource.)]
    (doto ds
      (.setDatabaseName dbname)
      (.setPassword password)
      (.setPort port)
      (.setServerName host)
      (.setUser user))
    (.getConnection ds)))

(defn- build-listener
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

(defn- register-listener [f postgres-config channel-names]
  (let [conn (pgjdbc-ng-conn postgres-config)
        close? (atom false)]
    (binding [*conn* conn]
      (with-open [stmt (.createStatement conn)]
        (.addNotificationListener
         conn
         (build-listener
          f
          #(when-not @close?
             (log/info "postgres notification listener disconnected. Reconnecting...")
             (register-listener f postgres-config channel-names))
          conn))
        (doseq [s channel-names]
          (.executeUpdate stmt (str "LISTEN " s)))))
    (fn []
      (reset! close? true)
      (log/info "Closing postgres notification listener.")
      (.close conn))))

(defn- register-channels
  "Takes a map of channel names to core.async channels. Registers
  postgres listeners for each channel that send each notification to
  the matching channel.

  Returns a thunk to close the listener."
  [channel-map postgres-config]
  (register-listener
   (fn [_process-id channel-name payload]
     (go (>! (channel-map channel-name) payload)))
   postgres-config
   (keys channel-map)))

(defn listener-handlers [listener]
  {"notification"
   (partial handle-notification listener)
   "notification_notification_subscriber"
   (partial handle-notification-notification-subscriber listener)})

(defn- handle-listener [f chan]
  (go
    (while true
      (try
        (let [x (<! chan)]
          (try
            (f x)
            (catch Throwable e
              (log/errorf "handle-listener error %s\n\n%s"
                          (with-out-str (print-cause-trace-custom e 20))
                          (pr-str x)))))
        (catch Throwable e
          (log/errorf "handle-listener error %s"
                      (with-out-str (print-cause-trace-custom e 20))))))))

(defrecord Listener [channels close-f handlers-f postgres]
  component/Lifecycle
  (start [this]
    (if close-f
      this
      (let [handlers (handlers-f this)
            channels (map-vals (fn [_] (chan)) handlers)]
        (doseq [[k f] handlers]
          (handle-listener f (get channels k)))
        (assoc this
               :channels channels
               :close-f (register-channels channels (:config postgres))))))
  (stop [this]
    (if close-f
      (do (close-f) (assoc this :channels nil :close-f nil))
      this)))

(defn listener []
  (map->Listener {:handlers-f listener-handlers}))
