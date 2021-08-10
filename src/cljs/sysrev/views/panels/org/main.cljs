(ns sysrev.views.panels.org.main
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [medley.core :refer [find-first]]
            [sysrev.data.core :refer [def-data reload]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer
             [Menu MenuItem Message MessageHeader]]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [setup-panel-state with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org]
                   :state state :get [panel-get] :set [panel-set])

(def-data ::orgs
  :loaded?  (fn [db] (-> (panel-get db)
                         (contains? :orgs)))
  :uri      (constantly "/api/orgs")
  :process  (fn [{:keys [db]} [] {:keys [orgs]}]
              {:db (panel-set db [:orgs] orgs)
               :dispatch [:self/orgs orgs]})
  :on-error (fn [{:keys [db error]} [] _]
              {:db (panel-set db [:orgs-error] (:message error))}))
(reg-sub  ::orgs #(panel-get % [:orgs]))

(reg-sub      ::org-id #(panel-get % [:org-id]))
(reg-event-db ::org-id [trim-v]
              (fn [db [org-id]] (panel-set db :org-id org-id)))

(reg-sub ::org
         :<- [::orgs]
         (fn [orgs [_ org-id]] (->> orgs (find-first #(= (:group-id %) org-id)))))

(reg-sub :org/name
         (fn [[_ org-id]] (subscribe [::org org-id]))
         #(:group-name %))

(reg-sub :org/permissions
         (fn [[_ org-id]] (subscribe [::org org-id]))
         #(:permissions %))

(reg-sub :org/owner?
         (fn [[_ org-id _match-dev?]]
           [(subscribe [:org/permissions org-id])
            (subscribe [:user/dev?])])
         (fn [[permissions dev?] [_ _ match-dev? _]]
           (boolean (or (some #{"owner"} permissions)
                        (and match-dev? dev?)))))

(reg-sub :org/admin?
         (fn [[_ org-id _match-dev?]]
           [(subscribe [:org/permissions org-id])
            (subscribe [:user/dev?])])
         (fn [[permissions dev?] [_ _ match-dev? _]]
           (boolean (or (some #{"admin"} permissions)
                        (and match-dev? dev?)))))

(reg-sub :org/owner-or-admin?
         (fn [[_ org-id match-dev?]]
           [(subscribe [:org/owner? org-id match-dev?])
            (subscribe [:org/admin? org-id match-dev?])])
         (fn [[owner? admin?]]
           (or owner? admin?)))

(defn- OrgContent [org-id child]
  (let [uri-fn #(str "/org/" org-id "/" %)
        active-panel @(subscribe [:active-panel])
        active? #(= active-panel [:org %])]
    (if false ; a check for org existance should be implemented here
      [Message {:negative true}
       [MessageHeader {:as "h4"} "Organizations Error"]
       [:p "There isn't an org here."]]
      [:div
       (when-not (some active? #{:plans :payment})
         [:div [:h2 @(subscribe [:org/name org-id])]
          [:nav
           [Menu {:pointing true :secondary true :attached "bottom"
                  :class "primary-menu"}
            [MenuItem {:id "org-projects" :name "Projects"
                       :href (uri-fn "projects")
                       :class (css [(active? :projects) "active"])} "Projects"]
            [MenuItem {:id "org-members" :name "Members"
                       :href (uri-fn "users")
                       :class (css [(active? :users) "active"])} "Members"]]]])
       [:div#org-content child]
       #_
       (when (nil? child)
         [Message {:negative true}
          [MessageHeader {:as "h4"} "Organizations Error"]
          [:p "This page does not exist."]])])))

(defn on-navigate-org [org-id to-panel]
  (let [_from-panel @(subscribe [:active-panel])]
    (dispatch [:set-panel-field [] {} to-panel])
    (dispatch [::org-id org-id])
    (dispatch [:set-active-panel to-panel])
    (reload :org/available-plans)
    (reload :org/default-source org-id)
    (reload :org/current-plan org-id)))

(defmethod panel-content [:org] []
  (fn [child]
    (when-let [org-id @(subscribe [::org-id])]
      (with-loader [[::orgs]] {}
        [OrgContent org-id child]))))
