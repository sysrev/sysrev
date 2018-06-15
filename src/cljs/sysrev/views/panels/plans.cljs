(ns sysrev.views.panels.plans
  (:require [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.stripe :as stripe]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.nav :refer [nav]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

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
  :uri (fn [] "/api/current-plan")
  :prereqs (fn [] [[:identity]])
  :process (fn [_ _ result]
             (ensure-state)
             (reset! (r/cursor state [:current-plan]) (:plan result))
             {}))

(def-action :subscribe-plan
  :uri (fn [] "/api/subscribe-plan")
  :content (fn [plan-name]
             {:plan-name plan-name})
  :process
  (fn [{:keys [db]} _ result]
    (cond (:created result)
          (do
            (reset! (r/cursor state [:changing-plan?]) false)
            (reset! (r/cursor state [:error-messsage]) nil)
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

(defn Plan
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
        [:div {:class "ui  attached secondary segment feature"}
         [:i {:class "icon red remove"}] "Item 1" ]
        [:div {:class "ui  attached segment feature"}
         [:i {:class "icon red remove"}] "Item 2" ]
        [:div {:class "ui  attached secondary segment feature"}
         [:i {:class "icon red remove"}] "Item 3" ]
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

(defn Plans []
  (let [color-vector ["teal" "blue" "violet"]
        plans (r/cursor state [:plans])]
    [:div {:class "ui three columns stackable grid"}
     (when (nil? @plans)
       {:key :loader}
       [:div.ui.small.active.loader])
     (when-not (nil? @plans)
       (doall (map-indexed
               (fn [index plan]
                 ^{:key (:product plan)}
                 [Plan (merge plan
                              {:color (nth color-vector index)})])
               (sort-by :amount @plans))))]))

(defn UpdatePaymentButton []
  [:div {:class "ui button primary"
         :on-click (fn [e]
                     (.preventDefault e)
                     (dispatch [:payment/set-calling-route! [:plans]])
                     (dispatch [:navigate [:payment]]))}
   "Update Payment Information"])

(defn ChangePlan []
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

(defmethod panel-content [:plans] []
  (fn [child]
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
             [Plans])])))))
