(ns sysrev.views.panels.user.plans
  (:require [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe reg-event-fx trim-v reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :refer [Segment Grid Column Row ListUI Item Button Message MessageHeader]]
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

;; based on:
;; https://codepen.io/caiosantossp/pen/vNazJy

#_(defn Plan
  "Props is:
  {:name   <string>
   :amount <integer> ; in USD cents"
  []
  (fn [{:keys [name amount product color]
        :or {color "blue"}}]
    (let [subscribed? (= product (:product @(r/cursor state [:current-plan])))
          changing-plan? (r/cursor state [:changing-plan?])
          selected-plan (r/cursor state [:selected-plan])
          plans (r/cursor state [:plans])]
      [:div {:class "column"}
       [:div {:class "ui segments plan"}
        [:div {:class (str "ui top attached segment inverted plan-title "
                           color)}
         [:span {:class "ui header"} name]]
        [:div {:class "ui attached segment feature"}
         [:div {:class "amount"}
          (if (= 0 amount)
            "Free"
            (str "$" (cents->dollars amount))) ]]
        [:div {:class "ui attached secondary segment feature"}
         [:i.red.times.icon] "Item 1" ]
        [:div {:class "ui attached segment feature"}
         [:i.red.times.icon] "Item 2" ]
        [:div {:class "ui attached secondary segment feature"}
         [:i.red.times.icon] "Item 3" ]
        [:div {:class (str "ui bottom attached button btn-plan "
                           color
                           (when subscribed?
                             " disabled"))
               :on-click (fn [e]
                           ;; this button shouldn't be enabled
                           ;; so this fn shouldn't run, but making
                           ;; sure of that
                           (when-not subscribed?
                             (if (= amount 0)
                               ;; this plan costs nothing, so no need to
                               ;; get payment information
                               (dispatch [:action [:subscribe-plan name]])
                               ;; we need to get the user's payment
                               (do
                                 (.preventDefault e)
                                 (reset! selected-plan
                                         (first (filter #(= product
                                                            (:product %))
                                                        @plans)))
                                 (reset! changing-plan? true)))))}
         (if subscribed?
           "Subscribed"
           [:div
            [:i {:class "cart icon"}]  "Select Plan"])]]])))

(defn DowngradePlan []
  (let [plans (r/cursor state [:plans])
        default-source (subscribe [:billing/default-source])
        error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
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
                         [Item "Unlimited public projects"]
                         [Item "Unlimited private projects"]]]
                       [Column {:width 8
                                :align "right"} [:h2 "$50 / month"]]]]]]]]
        [Grid [Row [Column
                    [:h3 "New Plan"]
                    [Segment
                     [Grid {:stackable true}
                      [Row
                       [Column {:width 8}
                        [:b "Basic"]
                        [ListUI
                         [Item "Unlimited public projects"]]]
                       [Column {:width 8
                                :align "right"} [:h2 "$0 / month"]]]]]
                    [:a {:href "/user/settings/billing"} "Back to Billing Settings"]]]]]
       [Column {:width 8}
        [Grid [Row [Column
                    [:h3 "Unsubscribe Summary"]
                    [ListUI {:divided true}
                     [:h4 "New Monthly Bill"]
                     [Item [:p "Basic plan ($0 / month)"]]
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
                       [Message {:negative true}
                        [MessageHeader "Upgrade Plan Error"]
                        [:p @error-message]])]]]]]]]]))

(defn UpgradePlan []
  (let [plans (r/cursor state [:plans])
        default-source (subscribe [:billing/default-source])
        error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
    [:div
     [:h1 "Upgrade your plan"]
     [Grid
      [Row
       [Column {:width 8}
        [Grid [Row [Column
                    [:h3 "UPGRADING TO"]
                    [Segment
                     [Grid {:stackable true}
                      [Row
                       [Column {:width 8}
                        [:b "Business Unlimited"]
                        [ListUI
                         [Item "Unlimited public projects"]
                         [Item "Unlimited private projects"]]]
                       [Column {:width 8
                                :align "right"} [:h2 "$50 / month"]]]]]
                    [:a {:href "/user/settings/billing"} "Back to Billing Settings"]]]]]
       [Column {:width 8}
        [Grid [Row [Column
                    [:h3 "Upgrade Summary"]
                    [ListUI {:divided true}
                     [:h4 "New Monthly Bill"]
                     [Item [:p "Unlimited plan ($50 / month)"]]
                     [:h4 "Billing Information"]
                     [Item [DefaultSource]]
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
                                           (dispatch [:action [:subscribe-plan "Unlimited"]]))
                               :disabled (or (empty? @default-source)
                                             @changing-plan?)}
                       "Upgrade Plan"]]
                     (when @error-message
                       [Message {:negative true}
                        [MessageHeader "Upgrade Plan Error"]
                        [:p @error-message]])]]]]]]]]))

#_(defn UpdatePaymentButton []
  [:div {:class "ui button primary"
         :on-click (fn [e]
                     (.preventDefault e)
                     (dispatch [:payment/set-calling-route! [:plans]])
                     (dispatch [:navigate [:payment]]))}
   "Update Payment Information"])

#_(defn ChangePlan []
  (let [selected-plan (r/cursor state [:selected-plan])
        changing-plan? (r/cursor state [:changing-plan?])
        error-message (r/cursor state [:error-message])
        need-card? (r/cursor stripe/state [:need-card?])]
    [:div [:h1 (str "You will be charged "
                    "$" (cents->dollars (:amount @selected-plan))
                    " per month and subscribed to the "
                    (:name @selected-plan) " plan.")]
     [:div {:class (str "ui button primary "
                        (when @need-card?
                          " disabled"))
            :on-click (fn [e]
                        (.preventDefault e)
                        (dispatch [:action [:subscribe-plan (:name @selected-plan)]])
                        (dispatch [:stripe/reset-error-message!]))}
      "Subscribe"]
     [:div {:class "ui button"
            :on-click
            (fn [e]
              (.preventDefault e)
              (reset! selected-plan nil)
              (reset! changing-plan? false)
              (reset! error-message nil)
              (reset! need-card? false))}
      "Cancel"]
     (when @error-message
       [:div.ui.red.header @error-message])
     [:br]
     [:br]
     (when @need-card?
       [UpdatePaymentButton])]))

(defmethod logged-out-content [:plans] []
  (logged-out-content :logged-out))

(defmethod panel-content [:plans] []
  (fn [child]
    (ensure-state)
    (stripe/ensure-state)
    (with-loader [[:identity]
                  ;;[:plans]
                  [:current-plan]
                  ] {}
      (let [changing-plan? (r/cursor state [:changing-plan?])
            updating-card? (r/cursor state [:updating-card?])
            need-card? (r/cursor stripe/state [:need-card?])
            error-message (r/cursor state [:error-message])
            current-plan (:name @(subscribe [:plans/current-plan]))]
        [:div
         #_(when-not @changing-plan?
           [UpdatePaymentButton])
         (when (= current-plan "Basic")
           [UpgradePlan])
         (when (= current-plan "Unlimited")
           [DowngradePlan])]))))

#_(defn UserPlans
  []
  (ensure-state)
  (stripe/ensure-state)
  (with-loader [[:identity]
                [:plans]
                [:current-plan]] {}
    (if (not @(subscribe [:self/logged-in?]))
      [LoginRegisterPanel]
      (let [changing-plan? (r/cursor state [:changing-plan?])
            updating-card? (r/cursor state [:updating-card?])
            need-card? (r/cursor stripe/state [:need-card?])
            error-message (r/cursor state [:error-message])]
        [:div.ui.segment
         (when-not @changing-plan?
           [UpdatePaymentButton])
         [:br]
         [:br]
         (when @changing-plan?
           [ChangePlan])
         (when-not @changing-plan?
           [Plans])]))))
