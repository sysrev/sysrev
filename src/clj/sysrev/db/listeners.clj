(ns sysrev.db.listeners
  (:require
   [clojure.core.async :refer [<! >! chan go]]
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [medley.core :refer [map-vals]]
   [sysrev.db.core :refer [*conn*]]
   [sysrev.notifications.listeners
    :refer [handle-notification handle-notification-notification-subscriber]]
   [sysrev.stacktrace :refer [print-cause-trace-custom]]
   [sysrev.util-lite.interface :as ul])
  (:import
   (com.impossibl.postgres.api.jdbc PGConnection PGNotificationListener)
   (com.impossibl.postgres.jdbc PGDataSource)))

(defn- pgjdbc-ng-conn ^PGConnection [postgres]
  (let [{:keys [dbname host password user]} (-> postgres :config :postgres)
        ds (PGDataSource.)]
    (doto ds
      (.setDatabaseName dbname)
      (.setPassword (or password ""))
      (.setPort (:bound-port postgres))
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
    (notification [_this process-id channel-name payload]
      (binding [*conn* conn]
        (f process-id channel-name payload)))
    (closed [_this]
      (closed-f))))

(defn- register-listener [f closed-f conn channel-names]
  (binding [*conn* conn]
    (with-open [stmt (.createStatement conn)]
      (.addNotificationListener
       conn
       (build-listener
        f
        closed-f
        conn))
      (doseq [s channel-names]
        (.executeUpdate stmt (str "LISTEN " s))))))

(defn- register-channels
  "Takes a map of channel names to core.async channels. Registers
  postgres listeners for each channel that send each notification to
  the matching channel.

  Returns a thunk to close the listener."
  [closed-f channel-map conn]
  (register-listener
   (fn [_process-id channel-name payload]
     (go (>! (channel-map channel-name) payload)))
   closed-f
   conn
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

(defrecord Listener [channels handlers-f postgres state]
  component/Lifecycle
  (start [this]
    (if state
      this
      (let [_ (log/info "Starting postgres notification listener.")
            handlers (handlers-f this)
            channels (map-vals (fn [_] (chan)) handlers)
            state (atom {:close? false
                         :conn (pgjdbc-ng-conn postgres)})
            closed-f (fn closed-f []
                       (ul/retry
                        {:interval-ms 1000 :n 100}
                        (let [{:keys [close? conn]}
                              #__ (swap! state
                                         (fn [{:keys [close? conn] :as state}]
                                           (if (or close? (not (.isClosed conn)))
                                             state
                                             (assoc state :conn (pgjdbc-ng-conn postgres)))))]
                          (when-not close?
                            (log/info "postgres notification listener disconnected. Reconnecting...")
                            (register-channels closed-f channels conn)))))]
        (doseq [[k f] handlers]
          (handle-listener f (get channels k)))
        (register-channels closed-f channels (:conn @state))
        (assoc this
               :channels channels
               :state state))))
  (stop [this]
    (if state
      (do
        (log/info "Closing postgres notification listener.")
        (swap! state assoc :close? true)
        (.close (:conn @state))
        (assoc this :channels nil :close? nil :state nil))
      this)))

(defn listener []
  (map->Listener {:handlers-f listener-handlers}))
