(ns sysrev.views.panels.user.plans
  (:require [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe reg-event-fx trim-v reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :as s :refer
             [Segment Grid Column Row ListUI ListItem Button]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.user.billing :refer [DefaultSource]]
            [sysrev.nav :refer [nav nav-scroll-top]])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:plans])

(def initial-state {:selected-plan nil
                    :changing-plan? false
                    ;;:need-card? false
                    ;;:updating-card? false
                    :error-message nil})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(def-data :plans
  :loaded? (fn [db] (contains? @state :plans))
  :uri (fn [] "/api/plans")
  :prereqs (fn [] [[:identity]])
  :process (fn [_ _ result]
             (ensure-state)
             (reset! (r/cursor state [:plans]) (:plans result))
             {}))

(def-data :current-plan
  :loaded? (fn [db] (contains? @state :current-plan))
  :uri (fn [] (str "/api/user/" @(subscribe [:self/user-id]) "/stripe/current-plan"))
  :prereqs (fn [] [[:identity]])
  :process (fn [_ _ result]
             (ensure-state)
             (reset! (r/cursor state [:current-plan]) (:plan result))
             {}))

(reg-sub :plans/current-plan
         (fn [db] @(r/cursor state [:current-plan])))

(def-action :subscribe-plan
  :uri (fn [] (str "/api/user/" @(subscribe [:self/user-id]) "/stripe/subscribe-plan"))
  :content (fn [plan-name]
             {:plan-name plan-name})
  :process
  (fn [{:keys [db]} _ result]
    (cond (:created result)
          (do
            (reset! (r/cursor state [:changing-plan?]) false)
            (reset! (r/cursor state [:error-messsage]) nil)
            (nav-scroll-top "/user/settings/billing")            
            {:dispatch [:fetch [:current-plan]]})))
  :on-error
  (fn [{:keys [db error]} _ _]
    (cond
      (= (-> error :type) "invalid_request_error")
      (reset! (r/cursor state [:error-message])
              "You must enter a valid payment method before subscribing to this plan")
      :else
      (reset! (r/cursor state [:error-message])
              (-> error :message)))
    (reset! (r/cursor stripe/state [:need-card?]) true)
    {}))

(defn cents->dollars
  "Converts an integer value of cents to dollars"
  [cents]
  (str (-> cents (/ 100) (.toFixed 2))))

(defn DowngradePlan []
  (let [default-source (subscribe [:billing/default-source])
        error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:h1 "Unsubscribe from your plan"]
         [Grid
          [Row
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe from"]
                        [Segment
                         [Grid {:stackable true}
                          [Row
                           [Column {:width 8}
                            [:b "Business Unlimited"]
                            [ListUI
                             [ListItem "Unlimited public projects"]
                             [ListItem "Unlimited private projects"]]]
                           [Column {:width 8
                                    :align "right"} [:h2 "$30 / month"]]]]]]]]
            [Grid [Row [Column
                        [:h3 "New Plan"]
                        [Segment
                         [Grid {:stackable true}
                          [Row
                           [Column {:width 8}
                            [:b "Basic"]
                            [ListUI
                             [ListItem "Unlimited public projects"]]]
                           [Column {:width 8
                                    :align "right"} [:h2 "$0 / month"]]]]]
                        [:a {:href "/user/settings/billing"} "Back to Billing Settings"]]]]]
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe Summary"]
                        [ListUI {:divided true}
                         [:h4 "New Monthly Bill"]
                         [ListItem [:p "Basic plan ($0 / month)"]]
                         (when (empty? @default-source)
                           [:a {:on-click
                                (fn [event]
                                  ($ event preventDefault)
                                  (dispatch [:payment/set-calling-route! (str "/user/plans")])
                                  (nav-scroll-top "/user/payment"))
                                :style {:cursor "pointer"}} "Add a payment method"])
                         [:div {:style {:margin-top "1em"
                                        :width "100%"}}
                          [Button {:color "green"
                                   :on-click (fn [event]
                                               (reset! changing-plan? true)
                                               (dispatch [:action [:subscribe-plan "Basic"]]))
                                   :disabled (or (empty? @default-source)
                                                 @changing-plan?)}
                           "Unsubscribe"]]
                         (when @error-message
                           [s/Message {:negative true}
                            [s/MessageHeader "Upgrade Plan Error"]
                            [:p @error-message]])]]]]]]]])
      :get-initial-state
      (fn [this]
        (reset! changing-plan? false)
        (reset! error-message nil))})))

(defn UpgradePlan []
  (let [default-source (subscribe [:billing/default-source])
        error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
    (r/create-class
     {:reagent-render (fn [this]
                        [:div
                         [:h1 "Upgrade your plan"]
                         [Grid
                          [Row [Column {:width 8}
                                [Grid [Row [Column
                                            [:h3 "UPGRADING TO"]
                                            [Segment
                                             [Grid {:stackable true}
                                              [Row
                                               [Column {:width 8}
                                                [:b "Business Unlimited"]
                                                [ListUI
                                                 [ListItem "Unlimited public projects"]
                                                 [ListItem "Unlimited private projects"]]]
                                               [Column {:width 8
                                                        :align "right"} [:h2 "$30 / month"]]]]]
                                            [:a {:href "/user/settings/billing"} "Back to Billing Settings"]]]]]
                           [Column {:width 8}
                            [Grid [Row [Column
                                        [:h3 "Upgrade Summary"]
                                        [ListUI {:divided true}
                                         [:h4 "New Monthly Bill"]
                                         [ListItem [:p "Unlimited plan ($30 / month)"]]
                                         [:h4 "Billing Information"]
                                         [ListItem [DefaultSource]]
                                         (when (or (empty? @default-source)
                                                   ((comp not nil?) @error-message))
                                           [:a {:on-click
                                                (fn [event]
                                                  ($ event preventDefault)
                                                  (dispatch [:payment/set-calling-route! (str "/user/plans")])
                                                  (reset! error-message nil)
                                                  (nav-scroll-top "/user/payment"))
                                                :style {:cursor "pointer"}} (if (empty? @default-source)
                                                                              "Add a payment method"
                                                                              "Change payment method")])
                                         [:div {:style {:margin-top "1em"
                                                        :width "100%"}}
                                          [Button {:color "green"
                                                   :on-click (fn [event]
                                                               (reset! changing-plan? true)
                                                               (dispatch [:action [:subscribe-plan "Unlimited"]]))
                                                   :disabled (or (empty? @default-source)
                                                                 @changing-plan?)}
                                           "Upgrade Plan"]]
                                         (when @error-message
                                           [s/Message {:negative true}
                                            [s/MessageHeader "Upgrade Plan Error"]
                                            [:p @error-message]])]]]]]]]])
      :get-initial-state
      (fn [this]
        (reset! changing-plan? false)
        (reset! error-message nil))})))

(defmethod logged-out-content [:plans] []
  (logged-out-content :logged-out))

(defmethod panel-content [:plans] []
  (fn [child]
    (ensure-state)
    (stripe/ensure-state)
    (with-loader [[:identity]
                  [:current-plan]] {}
      (let [changing-plan? (r/cursor state [:changing-plan?])
            updating-card? (r/cursor state [:updating-card?])
            need-card? (r/cursor stripe/state [:need-card?])
            error-message (r/cursor state [:error-message])
            current-plan (:name @(subscribe [:plans/current-plan]))]
        [:div
         (when (= current-plan "Basic")
           [UpgradePlan])
         (when (= current-plan "Unlimited")
           [DowngradePlan])]))))
