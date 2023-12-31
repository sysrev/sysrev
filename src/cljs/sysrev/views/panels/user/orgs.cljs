(ns sysrev.views.panels.user.orgs
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.create-org :refer [CreateOrg]]
            [sysrev.views.semantic :refer [Segment Header Divider]]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:user :orgs])

(def-data :user/orgs
  :loaded? (fn [db user-id] (-> (get-in db [:data :user-orgs])
                                (contains? user-id)))
  :uri (fn [user-id] (str "/api/user/" user-id "/orgs"))
  :process (fn [{:keys [db]} [user-id] {:keys [orgs]}]
             {:db (assoc-in db [:data :user-orgs user-id] orgs)})
  :on-error (fn [{:keys [db error]} [_] _]
              (js/console.error (pr-str error))
              {}))

(reg-sub :user/orgs
         (fn [db [_ user-id]]
           (get-in db [:data :user-orgs user-id])))

(defn- UserOrganization [{:keys [group-id group-name]}]
  [:div {:id (str "org-" group-id)
         :class "user-org-entry"
         :style {:margin-bottom "1em"}}
   [:a {:href (str "/org/" group-id "/projects") :on-click util/scroll-top}
    group-name]
   [Divider]])

(defn- UserOrgs [user-id]
  (with-loader [[:user/orgs user-id]] {}
    (when-let [orgs (seq @(subscribe [:user/orgs user-id]))]
      [Segment
       [Header {:as "h4" :dividing true} "Organizations"]
       [:div {:id "user-organizations"}
        (doall (for [org orgs] ^{:key (:group-id org)}
                 [UserOrganization org]))]])))

(defn- Panel []
  [:div
   (when @(subscribe [:user-panel/self?]) [CreateOrg])
   [UserOrgs @(subscribe [:user-panel/user-id])]])

(def-panel :uri "/user/:user-id/orgs" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (dispatch [:reload [:user/orgs user-id]])
              (dispatch [:set-active-panel panel]))
  :content [Panel])
