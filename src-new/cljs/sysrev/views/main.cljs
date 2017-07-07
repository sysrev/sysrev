(ns sysrev.views.main
  (:require
   [cljsjs.jquery]
   [cljsjs.semantic-ui]
   [reagent.core :as r]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.panels.login :refer [login-register-panel]]
   [sysrev.views.panels.overview]
   [sysrev.views.panels.article-list]
   [sysrev.views.panels.user-profile]
   [sysrev.views.panels.labels]
   [sysrev.views.panels.classify]
   [sysrev.views.panels.select-project]
   [sysrev.views.menu :refer [header-menu]]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db]]
   [sysrev.util :refer [full-size? mobile?]]
   [sysrev.shared.components :refer [loading-content]]
   [re-frame.db :refer [app-db]]))

(defmethod panel-content :default []
  [:div>h2 "route not found"])
(defmethod logged-out-content :default []
  [login-register-panel])

(defn active-panel-content []
  (if @(subscribe [:logged-in?])
    [panel-content] [logged-out-content]))

(defn notifier [entry]
  [:div])

(defn main-content []
  (if @(subscribe [:initialized?])
    [:div.main-content
     [header-menu]
     [:div.ui.container [active-panel-content]]
     [notifier @(subscribe [:active-notification])]]
    loading-content))
