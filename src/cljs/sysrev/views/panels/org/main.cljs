(ns sysrev.views.panels.org.main
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [active-route]]
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
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:org :main])

(def state (r/cursor app-db [:state :panels panel]))

(defn get-field [db path] (get-panel-field db path panel))
(defn set-field [db path val] (set-panel-field db path val panel))

(reg-sub :orgs #(get-field % [:orgs]))

(reg-event-db :set-orgs! (fn [db [_ orgs]]
                           (set-field db [:orgs] orgs)))

(defn read-orgs! []
  (let [retrieving-orgs? (r/cursor state [:retrieving-orgs?])
        orgs-error (r/cursor state [:orgs-error])]
    (reset! retrieving-orgs? true)
    (GET "/api/orgs"
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-orgs? false)
                     (dispatch [:self/set-orgs! (get-in response [:result :orgs])])
                     (dispatch [:set-orgs! (get-in response [:result :orgs])]))
          :error-handler (fn [error-response]
                           (reset! retrieving-orgs? false)
                           (reset! orgs-error (get-in error-response
                                                      [:response :error :message])))})))

(reg-event-fx :read-orgs! (fn [_ _] (read-orgs!) {}))

(reg-sub :orgs/get
         (fn [[_ org-id]] (subscribe [:orgs]))
         (fn [orgs [_ org-id]] (first (->> orgs (filter #(= (:group-id %) org-id))))))

(reg-sub :orgs/org-name
         (fn [[_ org-id]] (subscribe [:orgs/get org-id]))
         (fn [org] (:group-name org)))

(reg-sub :orgs/org-permissions
         (fn [[_ org-id]] (subscribe [:orgs/get org-id]))
         (fn [org] (:permissions org)))

(reg-sub :orgs/member-count
         (fn [[_ org-id]]
           [(subscribe [:orgs])])
         (fn [[orgs] [_ org-id]]
           (->> orgs
                (filter #(= (:id %) org-id))
                first
                :member-count)))

(reg-sub :orgs/org-id-from-url
         (fn [db _]
           (some->> (second (re-find #"/org/(\d*)/*" @active-route))
                    (ensure-pred string?)
                    (parse-integer))))

(defn OrgContent []
  (let [current-path active-route
        orgs (subscribe [:self/orgs])
        current-org-id (subscribe [:orgs/org-id-from-url])
        active-item (fn [current-path sub-path]
                      (let [url-match? (re-matches (re-pattern (str ".*" sub-path))
                                                   current-path)]
                        (css "item" [url-match? "active"])))
        uri-fn (fn [sub-path] (str "/org/" @current-org-id sub-path))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (if false ; a check for org existance should be implemented here
          [Message {:negative true}
           [MessageHeader {:as "h4"}
            "Organizations Error"]
           [:p "There isn't an org here."]]
          [:div
           (when-not (re-matches #"/org/(\d*)/plans" @current-path)
             [:nav
              [Menu {:pointing true
                     :secondary true
                     :attached "bottom"
                     :class "primary-menu"}
               [MenuItem {:name "Members"
                          :id "org-members"
                          :href (uri-fn "/users")
                          :class (active-item @current-path "/users")}
                "Members"]
               [MenuItem {:name "Projects"
                          :id "org-projects"
                          :href (uri-fn "/projects")
                          :class (active-item @current-path "/projects")}]
               #_[MenuItem {:name "Profile"
                            :id "org-profile"
                            :href "/org/profile"
                            :class (cond-> "item"
                                     (= @current-path "/org/profile") (str " active"))}
                  "Profile"]
               (when (some #{"admin" "owner"} @(subscribe [:orgs/org-permissions @current-org-id]))
                 [MenuItem {:name "Billing"
                            :id "org-billing"
                            :href (uri-fn "/billing")
                            :class (active-item @current-path "/billing")}
                  "Billing"])]])
           [:div {:id "org-content"}
            (condp re-matches @current-path
              #"/org/(\d*)/users"
              [OrgUsers {:org-id @current-org-id}]
              #"/org/(\d*)/projects"
              [OrgProjects {:org-id @current-org-id}]
              ;; #"/org/profile"
              ;; [:div
              ;;  (when-not (empty? @orgs)
              ;;    [:h1 (str (->> @orgs (filter #(= (:group-id %) @current-org-id)) first))])
              ;;  [:h1 "Profile settings go here"]]
              #"/org/(\d*)/billing"
              [OrgBilling {:org-id @current-org-id}]
              #"/org/(\d*)/plans"
              [OrgPlans {:org-id @current-org-id}]
              #"/org/(\d*)/payment"
              [OrgPayment {:org-id @current-org-id}]
              ;; default
              [:div {:style {:display "none"}}])]]))
      :component-did-mount (fn [] (dispatch [:read-orgs!]))})))

(defmethod logged-out-content [:org-settings] []
  (logged-out-content :logged-out))

(defmethod panel-content [:org-settings] []
  (fn [child]
    [OrgContent]))
