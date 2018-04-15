(ns sysrev.views.main
  (:require
   [cljsjs.jquery]
   [cljsjs.semantic-ui]
   [reagent.core :as r]
   [sysrev.views.base :refer
    [panel-content logged-out-content render-panel-tree]]
   [sysrev.views.panels.create-project]
   [sysrev.views.panels.login :refer [LoginRegisterPanel]]
   [sysrev.views.panels.password-reset]
   [sysrev.views.panels.payment]
   [sysrev.views.panels.pubmed]
   [sysrev.views.panels.plans]
   [sysrev.views.panels.select-project]
   [sysrev.views.panels.user-settings]
   [sysrev.views.panels.project.common]
   [sysrev.views.panels.project.add-articles]
   [sysrev.views.panels.project.main]
   [sysrev.views.panels.project.overview]
   [sysrev.views.panels.project.public-labels]
   [sysrev.views.panels.project.define-labels]
   [sysrev.views.panels.project.settings]
   [sysrev.views.panels.project.invite-link]
   [sysrev.views.panels.project.export-data]
   [sysrev.views.panels.project.user-labels]
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
  nil)

(defn active-panel-content []
  (let [active-panel @(subscribe [:active-panel])
        have-logged-out-content?
        ((comp not nil?) (logged-out-content active-panel))
        logged-in? @(subscribe [:self/logged-in?])]
    (if (or logged-in? (not have-logged-out-content?))
      [render-panel-tree active-panel]
      [:div#logged-out
       [logged-out-content active-panel]])))

(defn notifier [entry]
  [:div])

(defn main-content []
  (if @(subscribe [:initialized?])
    [:div.main-content
     {:class (if (or (not @(subscribe [:data/ready?]))
                     @(subscribe [:any-loading?]))
               "loading" "")}
     [header-menu]
     [:div.ui.container [active-panel-content]]
     [notifier @(subscribe [:active-notification])]]
    loading-content))
