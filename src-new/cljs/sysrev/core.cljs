(ns sysrev.core
  (:require [cljsjs.jquery]
            [reagent.core :as reagent]
            [re-frame.core :as re-frame :refer
             [dispatch dispatch-sync subscribe]]
            [pushy.core :as pushy]
            [sysrev.base :as base]
            [sysrev.fx]
            [sysrev.events.all]
            [sysrev.subs.all]
            [sysrev.data.core :as data]
            [sysrev.data.definitions]
            [sysrev.routes :as routes]
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
            [sysrev.shared.spec.notes]))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main-content]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (when base/debug?
    (enable-console-print!))
  (pushy/start! base/history)
  (re-frame/dispatch-sync [:initialize-db])
  (data/init-data)
  (mount-root))

(defonce started
  (do (init) true))
