(ns sysrev.views.panels.user.plans
  (:require [ajax.core :refer [GET]]
            [goog.uri.utils :as uri-utils]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$]]
            [re-frame.core :refer [dispatch subscribe reg-event-db trim-v reg-sub reg-event-fx]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :as s :refer
             [Segment Grid Column Row ListUI ListItem Button]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.user.billing :refer [DefaultSource]]
            [sysrev.nav :refer [nav nav-scroll-top]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [css]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state sr-defroute]]))

(setup-panel-state panel [:plans] {:state-var state
                                   :get-fn panel-get
                                   :set-fn panel-set
                                   :get-sub ::get
                                   :set-event ::set})

(defn- load-user-current-plan [db plan]
  (assoc-in db [:data :plans :current-plan] plan))

(def-data :user/current-plan
  :loaded? (fn [db user-id] (-> (get-in db [:data :plans])
                                (contains? :current-plan)))
  :uri (fn [user-id] (str "/api/user/" user-id "/stripe/current-plan"))
  :process (fn [{:keys [db]} _ {:keys [plan] :as result}]
             {:db (load-user-current-plan db plan)}))

(reg-sub :user/current-plan #(get-in % [:data :plans :current-plan]))

(reg-sub :user/on-subscribe-nav-to-url #(panel-get % :on-subscribe-nav-to-url))

(reg-event-db :user/set-on-subscribe-nav-to-url!
              (fn [db [_ url]] (panel-set db :on-subscribe-nav-to-url url)))

(def-action :user/subscribe-plan
  :uri (fn [user-id plan-name] (str "/api/user/" user-id "/stripe/subscribe-plan"))
  :content (fn [user-id plan-name]
             {:plan-name plan-name})
  :process (fn [{:keys [db]} [user-id _] {:keys [stripe-body plan] :as result}]
             (if (:created stripe-body)
               (let [nav-url (panel-get db :on-subscribe-nav-to-url)]
                 {:db (-> (panel-set db :changing-plan? false)
                          (panel-set :error-message nil)
                          (load-user-current-plan plan))
                  :dispatch-n (list [:self/reload-all-projects]
                                    [:nav-scroll-top nav-url])})))
  :on-error (fn [{:keys [db error]} _ _]
              (let [msg (if (= (:type error) "invalid_request_error")
                          "You must enter a valid payment method before subscribing to this plan"
                          (:message error))]
                {:db (-> (panel-set db :changing-plan? false)
                         (panel-set :error-message msg)
                         (stripe/panel-set :need-card? true))})))

(defn cents->dollars
  "Converts an integer value of cents to dollars"
  [cents]
  (str (-> cents (/ 100) (.toFixed 2))))

(defn price-summary
  [{:keys [tiers member-count]}]
  (let [base (->> tiers (map :flat_amount) (filter int?) (apply +))
        per-user (->> tiers (map :unit_amount) (filter int?) (apply +))
        up-to (->> tiers (map :up_to) (filter int?) first)
        monthly-bill (+ base (* (max 0 (- member-count 5)) per-user))]
    {:base base
     :per-user per-user
     :up-to up-to
     :monthly-bill monthly-bill}))

(defn Unlimited
  [{:keys [unlimited-plan-price
           unlimited-plan-name]}]
  [Segment
   [Grid {:stackable true}
    [Row
     [Column {:width 6}
      [:b unlimited-plan-name]
      [ListUI
       [ListItem "Unlimited public projects"]
       [ListItem "Unlimited private projects"]]]
     [Column {:width 10 :align "right"}
      (if (map? unlimited-plan-price)
        (let [{:keys [base per-user up-to monthly-bill]} (price-summary unlimited-plan-price)]
          [:div
           [Row [:h3 "$" (cents->dollars base) " / month"]]
           [Row [:h3 "up to 5 org members"]]
           [:br]
           [Row [:h3 "$ " (cents->dollars per-user) " / month"]]
           [Row [:h3 "per additional member"]]])
        [Row [:h3 (str "$" (cents->dollars unlimited-plan-price) " / month")] ])]]]])

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

(defn TogglePlanButton [{:keys [on-click class loading disabled] :as attrs} text]
  [Button (merge {:color "green"} attrs) text])

(defn DowngradePlan [{:keys [state
                             billing-settings-uri
                             on-downgrade
                             unlimited-plan-price
                             unlimited-plan-name]}]
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])
        current-path (uri-utils/getPath @active-route)]
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
                        [Unlimited {:unlimited-plan-price unlimited-plan-price
                                    :unlimited-plan-name unlimited-plan-name}]]]]
            [Grid [Row [Column
                        [:h3 "New Plan"]
                        [BasicPlan]
                        [:a {:href billing-settings-uri}
                         (if (= current-path "/user/plans")
                           "<< Back to user settings"
                           "<< Back to org settings")]]]]]
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe Summary"]
                        [ListUI {:divided true}
                         [:h4 "New Monthly Bill"]
                         [ListItem [:p "Basic plan ($0 / month)"]]
                         [:div {:style {:margin-top "1em" :width "100%"}}
                          [TogglePlanButton {:disabled @changing-plan?
                                             :on-click #(do (reset! changing-plan? true)
                                                            (on-downgrade))
                                             :class "unsubscribe-plan"
                                             :loading @changing-plan?}
                           "Unsubscribe"]]
                         (when @error-message
                           [s/Message {:negative true}
                            [s/MessageHeader "Change Plan Error"]
                            [:p @error-message]])]]]]]]]])
      :get-initial-state
      (fn [this]
        (reset! changing-plan? false)
        (reset! error-message nil))})))

(defn UpgradePlan [{:keys [state
                           billing-settings-uri
                           on-upgrade
                           default-source
                           on-add-payment-method
                           unlimited-plan-price
                           unlimited-plan-name]}]
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])
        mobile? (util/mobile?)
        current-path (uri-utils/getPath @active-route)]
    [:div
     (when-not mobile? [:h1 "Upgrade your plan"])
     [Grid {:stackable true :columns 2 :class "upgrade-plan"}
      [Column
       [Grid [Row [Column
                   [:h3 "UPGRADING TO"]
                   [Unlimited {:unlimited-plan-price unlimited-plan-price
                               :unlimited-plan-name unlimited-plan-name}]
                   (when-not mobile?
                     [:a {:href billing-settings-uri}
                      (if (= current-path "/user/plans")
                        "<< Back to user settings"
                        "<< Back to org settings")])]]]]
      [Column
       (let [no-default? (empty? default-source)]
         [Grid
          [Row
           [Column
            [:h3 "Upgrade Summary"]
            [ListUI {:divided true}
             [:h4 "New Monthly Bill"]
             [ListItem [:p (str "Unlimited plan ("
                                (if (map? unlimited-plan-price)
                                  (str "$" (-> unlimited-plan-price
                                               price-summary
                                               :monthly-bill
                                               cents->dollars)
                                       " / month")
                                  (str "$" (cents->dollars unlimited-plan-price) " / month"))
                                ")")]]
             [:h4 "Billing Information"]
             [ListItem [DefaultSource {:default-source default-source}]]
             (when (empty? @error-message)
               [:a.payment-method
                {:class (if no-default? "add-method" "change-method")
                 :style {:cursor "pointer"}
                 :on-click (util/wrap-prevent-default
                            #(do (reset! error-message nil)
                                 (on-add-payment-method)))}
                (if no-default?
                  "Add a payment method"
                  "Change payment method")])
             [:div {:style {:margin-top "1em" :width "100%"}}
              [TogglePlanButton {:disabled (or no-default? @changing-plan?)
                                 :on-click #(do (reset! changing-plan? true)
                                                (on-upgrade))
                                 :class "upgrade-plan"
                                 :loading @changing-plan?}
               "Upgrade Plan"]
              ;; https://stripe.com/docs/payments/cards/reusing-cards#mandates
              [:p {:style {:margin-top "1em"}} "By clicking 'Upgrade Plan' you authorize InSilica LLC to send instructions to the financial institution that issued your card to take payments from your card account in accordance with the above terms"]]
             (when @error-message
               [s/Message {:negative true}
                [s/MessageHeader "Change Plan Error"]
                [:p @error-message]])]]]])]]]))

(defn on-mount-user-plans
  []
  (let [self-id @(subscribe [:self/user-id])]
    (dispatch [::set :changing-plan? nil])
    (dispatch [::set :error-message nil])
    (dispatch [:set-active-panel [:plans]])
    (when self-id
      (dispatch [:data/load [:user/current-plan self-id]])
      (dispatch [:data/load [:user/default-source self-id]]))))

(defn UserPlans []
  (r/create-class
   {:reagent-render
    (fn [this]
      (when-let [self-id @(subscribe [:self/user-id])]
        (let [current-plan @(subscribe [:user/current-plan])
              default-source @(subscribe [:user/default-source])
              plan-args {:state state
                         :billing-settings-uri (str "/user/" self-id "/billing")
                         :unlimited-plan-price 1000
                         :unlimited-plan-name "Pro Plan"}]
          (condp = (:name current-plan)
            "Basic"
            [UpgradePlan
             (merge plan-args
                    {:default-source default-source
                     :on-upgrade #(dispatch [:action [:user/subscribe-plan self-id "Unlimited_User"]])
                     :on-add-payment-method
                     #(do
                        (dispatch [:stripe/set-calling-route! "/user/plans"])
                        (nav-scroll-top "/user/payment"))})]
            "Unlimited_User"
            [DowngradePlan
             (merge plan-args
                    {:on-downgrade #(dispatch [:action [:user/subscribe-plan self-id "Basic"]])})]
            [s/Message {:negative true}
             [s/MessageHeader "User Plans Error"]
             [:div.content
              [:p (str "Plan (" (:name current-plan) ") is not recognized for self-id: " self-id)]
              [:p (str "Active route: " @active-route)]]]))))
    :component-did-mount (fn [this]
                           (on-mount-user-plans))}))

(defmethod panel-content [:plans] []
  (fn [child] [UserPlans]))

(defmethod logged-out-content [:plans] []
  (logged-out-content :logged-out))

(sr-defroute user-plans "/user/plans" []
             (on-mount-user-plans))
