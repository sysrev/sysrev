(ns sysrev.core
  (:require [orchestra-cljs.spec.test :as t]
            [pushy.core :as pushy]
            [re-frame.core :refer [clear-subscription-cache! dispatch
                                   dispatch-sync reg-event-db reg-fx reg-sub]]
            [reagent.dom :as rdom]
            [sysrev.action.core :refer [def-action]]
            [sysrev.ajax]
            [sysrev.base :as base]
            [sysrev.data.core :as data]
            [sysrev.loading]
            [sysrev.nav]
            sysrev.sente
            [sysrev.shared.spec.article]
            [sysrev.shared.spec.core]
            [sysrev.shared.spec.keywords]
            [sysrev.shared.spec.labels]
            [sysrev.shared.spec.project]
            [sysrev.state.all]
            sysrev.user.interface.spec
            [sysrev.util :as util]
            [sysrev.views.article]
            [sysrev.views.main :refer [main-content]]))

(defonce current-width (atom nil))
(defonce current-height (atom nil))

(defn on-window-resize
  "Global handler for js/window resize event, attached in `mount-root`.

  1) Handle change in site layout (desktop, mobile, etc) by running
     `rdom/force-update-all` when window width changes. Uses
     `js/setTimeout` to avoid spamming action during stream of resize
     events.
  2) Adjust height of sidebar when window height changes."
  []
  (let [[cur-width cur-height] [@current-width @current-height]
        [start-width start-height] [(util/viewport-width)
                                    (util/viewport-height)]]
    (reset! current-width start-width)
    (reset! current-height start-height)
    (when (and cur-width (not= start-width cur-width))
      (js/setTimeout
       (fn [_]
         (let [end-width (util/viewport-width)]
           (when (= start-width end-width)
             (rdom/force-update-all))))
       100))
    (when (and cur-height (not= start-height cur-height))
      (util/update-sidebar-height))
    true))

(defonce last-scroll (atom nil))

(defn on-window-scroll []
  (let [current-millis (.getTime (js/Date.))
        last-millis @last-scroll]
    (when (or (not last-millis)
              (<= 30000 (- current-millis last-millis)))
      (reset! last-scroll current-millis)
      (dispatch [:review/window-scroll]))))

(defn mount-root []
  (clear-subscription-cache!)
  (let [el (.getElementById js/document "app")]
    (rdom/render [main-content] el))
  (js/window.addEventListener "resize" on-window-resize)
  (js/window.addEventListener "scroll" on-window-scroll))

(defn dev-setup []
  (when base/debug?
    (enable-console-print!)
    (t/instrument)))

(defn ^:dev/after-load on-jsload []
  (dev-setup)
  (mount-root))

(reg-sub :touch-event-time #(:touch-event-time %))

(reg-event-db :touch-event-time
              (fn [db] (assoc db :touch-event-time (js/Date.now))))

(reg-sub :mouse-event-time #(:mouse-event-time %))

(reg-event-db :mouse-event-time
              (fn [db] (assoc db :mouse-event-time (js/Date.now))))

(reg-sub :touchscreen?
         :<- [:touch-event-time] :<- [:mouse-event-time]
         (fn [[touch mouse]]
           (cond (and touch mouse)        (not (> (- mouse touch) 5000))
                 (and touch (nil? mouse)) true
                 (and mouse (nil? touch)) false
                 :else                    (if (util/full-size?) false true))))

(defn on-touchstart []
  (dispatch [:touch-event-time])
  true)

(defn on-mousedown []
  (dispatch [:mouse-event-time])
  true)

(defn start-touch-listener []
  (-> js/window (.addEventListener "touchstart" on-touchstart)))

(defn start-mouse-listener []
  (-> js/window (.addEventListener "mousedown" on-mousedown)))

(defonce recorded-errors (atom []))

(reg-fx ::fail #(js/console.error (pr-str %&)))

(def-action ::record-ui-errors
  :uri (constantly "/api/record-ui-errors")
  :content (fn [errors] {:errors errors})
  :process (constantly {})
  :timeout 10000)

(defn send-errors-to-server! []
  (let [errors (or (:error @base/console-logs) [])
        new-errors (subvec errors (count @recorded-errors))]
    (when (seq new-errors)
      (js/console.log "Sending" (count new-errors) "error report(s) to server")
      (dispatch [:action [::record-ui-errors new-errors]])
      (reset! recorded-errors errors))))

#_{:clj-kondo/ignore [:redundant-fn-wrapper]}
(defn start-send-errors-interval []
  ;; Wrap in fn to allow runtime redef
  (js/setInterval #(send-errors-to-server!) 1000))

(defn ^:export spec-instrument []
  (reset! base/tests-running true)
  (count (t/instrument)))

(defn ^:export init []
  (when base/debug? (enable-console-print!))
  (base/setup-console-hooks)
  (pushy/start! base/history)
  (dispatch-sync [:initialize-db])
  (data/init-data)
  (mount-root))

(defonce started
  (do (init)
      (start-send-errors-interval)
      (start-touch-listener)
      (start-mouse-listener)
      true))
