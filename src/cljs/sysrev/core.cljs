(ns sysrev.core
  (:require [cljsjs.jquery]
            [reagent.core :as reagent]
            [re-frame.core :as re-frame :refer
             [dispatch dispatch-sync subscribe reg-sub reg-event-db]]
            [pushy.core :as pushy]
            [sysrev.base :as base]
            [sysrev.ajax]
            [sysrev.nav]
            [sysrev.state.all]
            [sysrev.data.core :as data]
            [sysrev.action.core]
            [sysrev.loading]
            [sysrev.routes :as routes]
            [sysrev.views.article]
            [sysrev.views.main :refer [main-content]]
            [sysrev.spec.core]
            [sysrev.spec.db]
            [sysrev.spec.identity]
            [sysrev.shared.spec.core]
            [sysrev.shared.spec.article]
            [sysrev.shared.spec.project]
            [sysrev.shared.spec.labels]
            [sysrev.shared.spec.users]
            [sysrev.shared.spec.keywords]
            [sysrev.shared.spec.notes]
            [sysrev.util :as util]))

(defonce current-width (atom nil))

(defn force-update-soon
  "Force full render update, with js/setTimeout delay to avoid spamming
  updates while window is being resized."
  []
  (let [cur-size @current-width
        start-size (util/viewport-width)]
    (reset! current-width start-size)
    (when (and cur-size (not= start-size cur-size))
      (js/setTimeout
       (fn [_]
         (let [end-size (util/viewport-width)]
           (when (and (= start-size end-size))
             (reagent/force-update-all))))
       100))
    true))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main-content]
                  (.getElementById js/document "app"))
  (-> js/window (.addEventListener
                 "resize"
                 force-update-soon)))

(reg-sub
 :touch-event-time
 (fn [db _] (:touch-event-time db)))

(reg-event-db
 :touch-event-time
 (fn [db] (assoc db :touch-event-time (js/Date.now))))

(reg-sub
 :mouse-event-time
 (fn [db _] (:mouse-event-time db)))

(reg-event-db
 :mouse-event-time
 (fn [db] (assoc db :mouse-event-time (js/Date.now))))

(reg-sub
 :touchscreen?
 :<- [:touch-event-time]
 :<- [:mouse-event-time]
 (fn [[touch mouse]]
   (cond (and touch mouse)        (not (> (- mouse touch) 1000))
         (and touch (nil? mouse)) true
         (and mouse (nil? touch)) false
         :else                    (if (util/full-size?) false true))))

(defn on-touchstart []
  (dispatch [:touch-event-time])
  true)

(defn on-pointerdown []
  (dispatch [:mouse-event-time])
  true)

(defn start-touch-listener []
  (-> js/window (.addEventListener "touchstart" on-touchstart)))

(defn start-mouse-listener []
  (-> js/window (.addEventListener "pointerdown" on-pointerdown)))

(defn ^:export init []
  (when base/debug?
    (enable-console-print!))
  (pushy/start! base/history)
  (re-frame/dispatch-sync [:initialize-db])
  (data/init-data)
  (mount-root))

(defonce started
  (do (init)
      (start-touch-listener)
      (start-mouse-listener)
      true))
