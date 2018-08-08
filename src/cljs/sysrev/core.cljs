(ns sysrev.core
  (:require [cljsjs.jquery]
            [reagent.core :as reagent]
            [re-frame.core :as re-frame :refer
             [dispatch dispatch-sync subscribe]]
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

(defn force-update-soon
  "Force full render update, with js/setTimeout delay to avoid spamming
  updates while window is being resized."
  []
  (let [start-size (util/viewport-width)]
    (js/setTimeout
     (fn [_]
       (let [end-size (util/viewport-width)]
         (when (= start-size end-size)
           (reagent/force-update-all))))
     100)))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main-content]
                  (.getElementById js/document "app"))
  (-> js/window (.addEventListener
                 "resize"
                 force-update-soon)))

(defn ^:export init []
  (when base/debug?
    (enable-console-print!))
  (pushy/start! base/history)
  (re-frame/dispatch-sync [:initialize-db])
  (data/init-data)
  (mount-root))

(defonce started
  (do (init) true))
