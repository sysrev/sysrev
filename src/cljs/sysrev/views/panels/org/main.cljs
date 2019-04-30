(ns sysrev.views.panels.org.main
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [active-route]]
            [sysrev.state.ui :refer [get-panel-field set-panel-field]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.org.billing :refer [OrgBilling]]
            [sysrev.views.panels.org.projects :refer [OrgProjects]]
            [sysrev.views.panels.org.users :refer [OrgUsers]]
            [sysrev.views.semantic :refer [Segment Header Menu MenuItem Dropdown]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:org :main])

(def state (r/cursor app-db [:state :panels panel]))

(defn get-field [db path] (get-panel-field db path panel))
(defn set-field [db path val] (set-panel-field db path val panel))

(reg-sub :current-org #(get-field % [:current-org-id]))

(reg-event-db
 :set-current-org!
 (fn [db [_ org-id]]
   (set-field db [:current-org-id] org-id)))

(defn read-orgs!
  []
  (let [retrieving-orgs? (r/cursor state [:retrieving-orgs?])
        orgs-error (r/cursor state [:orgs-error])
        orgs (r/cursor state [:orgs])
        current-org-id (r/cursor state [:current-org-id])]
    (reset! retrieving-orgs? true)
    (GET "/api/orgs"
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-orgs? false)
                     (reset! orgs (get-in response [:result :orgs]))
                     (when (and (not (empty? @orgs))
                                (nil? @current-org-id))
                       (dispatch [:set-current-org! (->> @orgs (sort-by :id) first :id)])
                       ;; for plans to be able to keep up
                       (dispatch [:fetch [:org-current-plan]])))
          :error-handler (fn [error-response]
                           (reset! retrieving-orgs? false)
                           (reset! orgs-error (get-in error-response
                                                      [:response :error :message])))})))

(reg-sub :orgs #(get-field % [:orgs]))

(reg-event-fx :read-orgs! (fn [_ _] (read-orgs!) {}))

(reg-sub :current-org-name
         (fn []
           [(subscribe [:orgs])
            (subscribe [:current-org])])
         (fn [[orgs current-org]]
           (->> orgs
                (filter #(= (:id %) current-org))
                first
                :group-name)))

(reg-sub :current-org-permissions
         (fn []
           [(subscribe [:orgs])
            (subscribe [:current-org])])
         (fn [[orgs current-org]]
           (->> orgs
                (filter #(= (:id %) current-org))
                first
                :permissions)))

(defn OrgContent
  []
  (let [current-path active-route
        orgs (r/cursor state [:orgs])
        current-org-id (r/cursor state [:current-org-id])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [Segment {:attached "top"
                   :aligned "middle"}
          [Header {:as "h4"}
           "Organization Settings"]]
         [Menu {:pointing true
                :secondary true
                :attached "bottom"
                :class "primary-menu"}
          [MenuItem {:name "Users"
                     :id "org-users"
                     :href "/org/users"
                     :class (cond-> "item"
                              (= @current-path "/org/users") (str " active"))}
           "Users"]
          [MenuItem {:name "Projects"
                     :id "org-projects"
                     :href "/org/projects"
                     :class (cond-> "item"
                              (= @current-path "/org/projects") (str " active"))}]
          #_[MenuItem {:name "Profile"
                     :id "org-profile"
                     :href "/org/profile"
                     :class (cond-> "item"
                              (= @current-path "/org/profile") (str " active"))}
           "Profile"]
          (when (some #{"admin" "owner"} @(subscribe [:current-org-permissions]))
            [MenuItem {:name "Billing"
                       :id "org-billing"
                       :href "/org/billing"
                       :class (cond-> "item"
                                (= @current-path "/org/billing") (str " active"))}
             "Billing"])
          (when-not (empty? @orgs)
            [MenuItem {:position "right"}
             [Dropdown {:id "change-org-dropdown"
                        :options (map #(hash-map :text (:group-name %)
                                                 :value (:id %)) @orgs)
                        :value @current-org-id
                        :on-change (fn [event data]
                                     (reset! current-org-id
                                             ($ data :value)))}]])]
         [:div {:id "org-content"}
          (condp re-matches @current-path
            #"/org/users"
            [OrgUsers]
            #"/org/projects"
            [OrgProjects]
            ;; #"/org/profile"
            ;; [:div
            ;;  (when-not (empty? @orgs)
            ;;    [:h1 (str (->> @orgs (filter #(= (:id %) @current-org-id)) first))])
            ;;  [:h1 "Profile settings go here"]]
            #"/org/billing"
            [OrgBilling]
            ;; default
            [:div {:style {:display "none"}}])]])
      :component-did-mount (fn [this]
                             (read-orgs!))})))

(defmethod logged-out-content [:org-settings] []
  (logged-out-content :logged-out))

(defmethod panel-content [:org-settings] []
  (fn [child]
    [OrgContent]))
