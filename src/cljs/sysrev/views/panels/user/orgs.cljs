(ns sysrev.views.panels.user.orgs
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.util :as util :refer [wrap-prevent-default]]
            [sysrev.views.panels.orgs :refer [CreateOrg]]
            [sysrev.views.semantic :refer [Segment Header Divider]]
            [sysrev.views.panels.user.profile :as user-profile])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [setup-panel-state]]))

;; Using same panel value as sysrev.views.panels.user.profile
(setup-panel-state panel [:user :profile] {:state-var state})

(def-data :user/orgs
  :loaded? (fn [db user-id] (-> (get-in db [:data :user-orgs])
                                (contains? user-id)))
  :uri (fn [user-id] (str "/api/user/" user-id "/orgs"))
  :process (fn [{:keys [db]} [user-id] {:keys [orgs]}]
             {:db (assoc-in db [:data :user-orgs user-id] orgs)})
  :on-error (fn [{:keys [db error]} [user-id] _]
              (js/console.error (pr-str error))
              {}))

(reg-sub :user/orgs
         (fn [db [_ user-id]]
           (get-in db [:data :user-orgs user-id])))

(defn UserOrganization [{:keys [group-id group-name]}]
  [:div {:id (str "org-" group-id)
         :class "user-org-entry"
         :style {:margin-bottom "1em"}}
   [:a {:href "#" :on-click (wrap-prevent-default
                             #(nav-scroll-top (str "/org/" group-id "/users")))}
    group-name]
   [Divider]])

(defn UserOrgs [user-id]
  (let [orgs (subscribe [:user/orgs user-id])]
    (r/create-class
     {:reagent-render (fn [this]
                        (when (seq @orgs)
                          [Segment
                           [Header {:as "h4" :dividing true} "Organizations"]
                           [:div {:id "user-organizations"}
                            (doall (for [org @orgs] ^{:key (:group-id org)}
                                     [UserOrganization org]))]]))
      :component-did-mount (fn [this]
                             (dispatch [:data/load [:user/orgs user-id]]))})))

(defn Orgs [{:keys [user-id]}]
  [:div
   (when @(subscribe [:users/is-path-user-id-self?])
     [CreateOrg])
   [UserOrgs user-id]])
