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
            [sysrev.nav :refer [nav nav-scroll-top]]
            [sysrev.util :as util])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:plans])

(def initial-state {:selected-plan nil
                    :changing-plan? false
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

(defn BusinessUnlimited
  []
  [Segment
   [Grid {:stackable true}
    [Row
     [Column {:width 8}
      [:b "Business Unlimited"]
      [ListUI
       [ListItem "Unlimited public projects"]
       [ListItem "Unlimited private projects"]]]
     [Column {:width 8 :align "right"}
      [:h2 "$50 / month"]]]]])

(defn BasicPlan []
  [Segment
   [Grid {:stackable true}
    [Row
     [Column {:width 8}
      [:b "Basic"]
      [ListUI
       [ListItem "Unlimited public projects"]]]
     [Column {:width 8 :align "right"}
      [:h2 "$0 / month"]]]]])

(defn TogglePlanButton
  [{:keys [disabled on-click class]} & [text]]
  [Button
   {:class class
    :color "green"
    :on-click
    on-click
    :disabled disabled}
   text])

(defn DowngradePlan [{:keys [billing-settings-route
                             unsubscribe-button-on-click
                             downgrade-dispatch
                             default-source]}]
  (let [error-message (r/cursor state [:error-message])
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
                        [BusinessUnlimited]]]]
            [Grid [Row [Column
                        [:h3 "New Plan"]
                        [BasicPlan]
                        [:a {:href billing-settings-route} "Back to Billing Settings"]]]]]
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe Summary"]
                        [ListUI {:divided true}
                         [:h4 "New Monthly Bill"]
                         [ListItem [:p "Basic plan ($0 / month)"]]
                         [:div {:style {:margin-top "1em" :width "100%"}}
                          [TogglePlanButton {:disabled (or @changing-plan?)
                                             :on-click #(do (reset! changing-plan? true)
                                                            (downgrade-dispatch))
                                             :class "unsubscribe-plan"} "Unsubscribe"]]
                         (when @error-message
                           [s/Message {:negative true}
                            [s/MessageHeader "Change Plan Error"]
                            [:p @error-message]])]]]]]]]])
      :get-initial-state
      (fn [this]
        (reset! changing-plan? false)
        (reset! error-message nil))})))

(defn UpgradePlan [{:keys [billing-settings-route
                           upgrade-dispatch
                           default-source
                           get-default-source
                           add-payment-method]}]
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:h1 "Upgrade your plan"]
         [Grid
          [Row
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "UPGRADING TO"]
                        [BusinessUnlimited]
                        [:a {:href billing-settings-route} "Back to Billing Settings"]]]]]
           [Column {:width 8}
            (let [no-default? (empty? @default-source)]
              [Grid
               [Row
                [Column
                 [:h3 "Upgrade Summary"]
                 [ListUI {:divided true}
                  [:h4 "New Monthly Bill"]
                  [ListItem [:p "Unlimited plan ($50 / month)"]]
                  [:h4 "Billing Information"]
                  [ListItem [DefaultSource {:get-default-source get-default-source
                                            :default-source default-source}]]
                  (when (empty? @error-message)
                    [:a.payment-method
                     {:class (if no-default? "add-method" "change-method")
                      :style {:cursor "pointer"}
                      :on-click (util/wrap-prevent-default
                                 #(do (reset! error-message nil)
                                      (add-payment-method)))}
                     (if no-default?
                       "Add a payment method"
                       "Change payment method")])
                  [:div {:style {:margin-top "1em" :width "100%"}}
                   [TogglePlanButton {:disabled (or no-default? @changing-plan?)
                                      :on-click
                                      ;; TODO: This needs to be a custom fn
                                      #(do (reset! changing-plan? true)
                                           (upgrade-dispatch))
                                      :class "upgrade-plan"}
                    "Upgrade Plan"]]
                  (when @error-message
                    [s/Message {:negative true}
                     [s/MessageHeader "Change Plan Error"]
                     [:p @error-message]])]]]])]]]])
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
           [UpgradePlan {:billing-settings-route "/user/settings/billing"
                         :upgrade-dispatch (fn []
                                             (dispatch [:action [:subscribe-plan "Unlimited"]]))
                         :default-source (subscribe [:stripe/default-source "user" @(subscribe [:self/user-id])])
                         :get-default-source stripe/get-user-default-source
                         :add-payment-method #(do (dispatch [:payment/set-calling-route! "/user/plans"])
                                                  (dispatch [:navigate [:payment]]))}])
         (when (= current-plan "Unlimited")
           [DowngradePlan {:billing-settings-route "/user/settings/billing"
                           :downgrade-dispatch (fn []
                                                 (dispatch [:action [:subscribe-plan "Basic"]]))
                           :default-source (subscribe [:stripe/default-source "user" @(subscribe [:self/user-id])])}])]))))
