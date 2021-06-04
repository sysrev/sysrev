(ns sysrev.sente
  (:require [re-frame.core :refer [dispatch reg-event-fx]]
            [taoensso.sente :as sente]))

(defonce router (atom nil))

(defonce ch-chsk (atom nil))
(defonce chsk-send! (atom nil))
(defonce csrf (atom nil))

(def config {:type :auto
             :packer :edn})

(defn event-msg-handler
  [{:as _ev-msg :keys [id ?data event]}]
  (let [[event & args] ?data]
    (when (and (= :chsk/recv id)
               (= :re-frame/dispatch event))
      (apply dispatch args))))

(defn create-client! [csrf-token]
  (let [{:keys [ch-recv send-fn]}
        #__ (sente/make-channel-socket-client! "/api/chsk" csrf-token config)]
    (reset! ch-chsk ch-recv)
    (reset! chsk-send! send-fn)))

(defn stop-router! []
  (when-let [stop-f @router] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router (sente/start-client-chsk-router! @ch-chsk event-msg-handler)))

(defn start-sente! [csrf-token]
  (create-client! csrf-token)
  (start-router!)
  (reset! csrf csrf-token))

(reg-event-fx :connect-sente
              (fn [_ [_ csrf-token]]
                (when-not (= csrf-token @csrf)
                  (start-sente! csrf-token))
                {}))
