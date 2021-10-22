(ns sysrev.sente
  (:require [clojure.core.async :refer [<!!]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sysrev.reviewer-time.interface :as reviewer-time]
            [sysrev.stacktrace :refer [print-cause-trace-custom]]
            [sysrev.web.app :refer [current-user-id]]
            [taoensso.sente :refer [make-channel-socket!]]
            [taoensso.sente.server-adapters.aleph :refer [get-sch-adapter]]))

(defn sente-send! [sente & args]
  (apply (get-in sente [:chsk :send-fn]) args))

(defn sente-dispatch! [sente client-id re-frame-event]
  (sente-send! sente client-id [:re-frame/dispatch re-frame-event]))

(defn sente-connected-users [sente]
  (:any @(get-in sente [:chsk :connected-uids])))

(defrecord Sente [chsk receive-f]
  component/Lifecycle
  (start [this]
    (if chsk
      this
      (let [chsk (make-channel-socket! (get-sch-adapter)
                                       {:user-id-fn current-user-id})
            this (assoc this :chsk chsk)]
        (when receive-f
          (receive-f this (:ch-recv chsk)))
        this)))
  (stop [this]
    (if-not chsk
      this
      (assoc this :chsk nil))))

(defn sente [& {:keys [receive-f]}]
  (map->Sente {:receive-f receive-f}))

(defn- full-name [x]
  (when x
    (if (string? x)
      x
      (if (simple-ident? x)
        (name x)
        (str (namespace x) "/" (name x))))))

(defn- handle-message! [sente item]
  (let [{:keys [event]} item
        [kind data] event]
    (case kind
      :review/record-reviewer-event
      (let [[reframe-event {:keys [article-id project-id]}] data]
        (reviewer-time/create-events!
         (get-in sente [:postgres :datasource])
         [{:article-id article-id
           :event-type (full-name reframe-event)
           :project-id project-id
           :user-id (:uid item)}]))

      nil)))

(defn receive-sente-channel! [sente chan]
  (future
    (while true
      (try
        (let [x (<!! chan)]
          (try
            (handle-message! sente x)
            (catch Throwable e
              (log/errorf "receive-sente-channel! error %s\n\n%s"
                          (with-out-str (print-cause-trace-custom e 20))
                          (pr-str x)))))
        (catch Throwable e
          (log/errorf "receive-sente-channel! error %s"
                      (with-out-str (print-cause-trace-custom e 20))))))))
