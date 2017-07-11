(ns sysrev.views.main
  (:require
   [cljsjs.jquery]
   [cljsjs.semantic-ui]
   [reagent.core :as r]
   [sysrev.views.base :refer
    [panel-content logged-out-content render-panel-tree]]
   [sysrev.views.panels.login :refer [login-register-panel]]
   [sysrev.views.panels.select-project]
   [sysrev.views.panels.project.main]
   [sysrev.views.panels.project.overview]
   [sysrev.views.panels.project.article-list]
   [sysrev.views.panels.project.labels]
   [sysrev.views.panels.project.settings]
   [sysrev.views.panels.project.invite-link]
   [sysrev.views.panels.project.member-account]
   [sysrev.views.panels.project.review]
   [sysrev.views.menu :refer [header-menu]]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db]]
   [sysrev.util :refer [full-size? mobile?]]
   [sysrev.shared.components :refer [loading-content]]
   [re-frame.db :refer [app-db]]))

(defmethod panel-content :default []
  (fn [child]
    [:div
     [:h2 "route not found"]
     child]))
(defmethod logged-out-content :default []
  [login-register-panel])

(defn active-panel-content []
  (if @(subscribe [:logged-in?])
    [render-panel-tree @(subscribe [:active-panel])]
    [logged-out-content]))

(defn notifier [entry]
  [:div])

(defn main-content []
  (if @(subscribe [:initialized?])
    [:div.main-content
     [header-menu]
     [:div.ui.container [active-panel-content]]
     [notifier @(subscribe [:active-notification])]]
    loading-content))