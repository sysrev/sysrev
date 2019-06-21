(ns sysrev.views.panels.org.main
  (:require [clojure.string :as str]
            [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.ui :refer [get-panel-field set-panel-field]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.org.billing :refer [OrgBilling]]
            [sysrev.views.panels.org.payment :refer [OrgPayment]]
            [sysrev.views.panels.org.plans :refer [OrgPlans]]
            [sysrev.views.panels.org.projects :refer [OrgProjects]]
            [sysrev.views.panels.org.users :refer [OrgUsers]]
            [sysrev.views.semantic :refer
             [Segment Header Menu MenuItem Dropdown Message MessageHeader]]
            [sysrev.shared.util :as sutil :refer [ensure-pred parse-integer css]])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [setup-panel-state]]))

(setup-panel-state panel [:org :main] {:state-var state
                                       :get-fn panel-get
                                       :set-fn panel-set})

(reg-sub ::orgs #(panel-get % [:orgs]))

(def-data :self-orgs
  ;; TODO: don't keep this data in 2 places?
  :loaded?  (fn [db] (and (-> (get-in db [:state :self])  (contains? :orgs))
                          (-> (panel-get db)              (contains? :orgs))))
  :uri      (fn [] "/api/orgs")
  :process  (fn [{:keys [db]} [] {:keys [orgs] :as result}]
              {:db (-> (assoc-in db [:state :self :orgs] orgs)
                       (panel-set [:orgs] orgs))})
  :on-error (fn [{:keys [db error]} [] _]
              {:db (panel-set db [:orgs-error] (:message error))}))

(reg-sub ::org
         (fn [[_ org-id]] (subscribe [::orgs]))
         (fn [orgs [_ org-id]] (first (->> orgs (filter #(= (:group-id %) org-id))))))

(reg-sub :org/name
         (fn [[_ org-id]] (subscribe [::org org-id]))
         (fn [org] (:group-name org)))

(reg-sub :org/permissions
         (fn [[_ org-id]] (subscribe [::org org-id]))
         (fn [org] (:permissions org)))

(reg-sub :org/member-count
         (fn [[_ org-id]] [(subscribe [::org org-id])])
         (fn [org] (:member-count org)))

(defn org-id-from-url []
  (parse-integer (second (re-find #"/org/(\d+)" @active-route))))

(defn OrgContent []
  (let [orgs (subscribe [:self/orgs])
        uri-fn (fn [sub-path] (str "/org/" (org-id-from-url) "/" sub-path))
        active? (fn [sub-path] (str/includes? @active-route (uri-fn sub-path)))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when-let [org-id (org-id-from-url)]
          (if false ; a check for org existance should be implemented here
            [Message {:negative true}
             [MessageHeader {:as "h4"} "Organizations Error"]
             [:p "There isn't an org here."]]
            [:div
             (when-not (active? "plans")
               [:nav
                [Menu {:class "primary-menu" :pointing true :secondary true :attached "bottom"}
                 [MenuItem {:id "org-members" :href (uri-fn "users")
                            :name "Members" :class (css [(active? "users") "active"])}
                  "Members"]
                 [MenuItem {:id "org-projects" :href (uri-fn "projects")
                            :name "Projects" :class (css [(active? "projects") "active"])}]
                 #_
                 [MenuItem {:id "org-profile" :href "/org/profile"
                            :name "Profile" :class (css [(= @active-route "/org/profile") "active"])}
                  "Profile"]
                 (when (some #{"admin" "owner"} @(subscribe [:org/permissions org-id]))
                   [MenuItem {:id "org-billing" :href (uri-fn "billing")
                              :name "Billing" :class (css [(active? "billing") "active"])}
                    "Billing"])]])
             [:div {:id "org-content"}
              (condp re-matches @active-route
                #"/org/(\d*)/users"     [OrgUsers {:org-id org-id}]
                #"/org/(\d*)/projects"  [OrgProjects {:org-id org-id}]
                #"/org/(\d*)/billing"   [OrgBilling {:org-id org-id}]
                #"/org/(\d*)/plans"     [OrgPlans {:org-id org-id}]
                #"/org/(\d*)/payment"   [OrgPayment {:org-id org-id}]
                #_
                #"/org/profile"
                #_
                [:div
                 (when-not (empty? @orgs)
                   [:h1 (str (->> @orgs (filter #(= (:group-id %) @current-org-id)) first))])
                 [:h1 "Profile settings go here"]]
                ;; default
                [Message {:negative true}
                 [MessageHeader {:as "h4"} "Organizations Error"]
                 [:p "This page does not exist."]])]])))
      :component-did-mount (fn []
                             (dispatch [:require [:self-orgs]])
                             (dispatch [:reload [:self-orgs]]))})))

(defmethod logged-out-content [:org-settings] []
  (logged-out-content :logged-out))

(defmethod panel-content [:org-settings] []
  (fn [child]
    [OrgContent]))
