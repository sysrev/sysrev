(ns sysrev.views.panels.user.plans
  (:require [goog.uri.utils :as uri-utils]
            [medley.core :as medley]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe reg-sub]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.stripe :as stripe :refer [StripeCardInfo]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.views.semantic :as S :refer
             [Segment SegmentGroup Grid Column Row ListUI ListItem Button Loader Radio]]
            [sysrev.views.panels.user.billing :refer [DefaultSource]]
            [sysrev.views.panels.pricing :as pricing :refer [FreeBenefits]]
            [sysrev.util :as util :refer [sum]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:plans]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :user/available-plans
  :loaded? (fn [db _user-id] (-> (get-in db [:data :plans :available-plans])))
  :uri (fn [user-id] (str "/api/user/" user-id "/stripe/available-plans"))
  :process (fn [{:keys [db]} _ {:keys [plans]}]
             {:db (assoc-in db [:data :plans :available-plans]
                            plans)}))

(reg-sub :user/available-plans #(get-in % [:data :plans :available-plans]))

(defn- load-user-current-plan [db plan]
  (assoc-in db [:data :plans :current-plan] plan))

(def-data :user/current-plan
  :loaded? (fn [db _user-id] (-> (get-in db [:data :plans])
                                 (contains? :current-plan)))
  :uri (fn [user-id] (str "/api/user/" user-id "/stripe/current-plan"))
  :process (fn [{:keys [db]} _ {:keys [plan]}]
             {:db (load-user-current-plan db plan)}))

(reg-sub :user/current-plan #(get-in % [:data :plans :current-plan]))

(def-action :user/subscribe-plan
  :uri (fn [user-id _] (str "/api/user/" user-id "/stripe/subscribe-plan"))
  :content (fn [_ new-plan] {:plan new-plan})
  :process (fn [{:keys [db]} [user-id _] {:keys [stripe-body plan]}]
             (when (:created stripe-body)
               (let [nav-url (-> (or (:on_subscribe_uri (util/get-url-params))
                                     (str "/user/" user-id "/billing")))]
                 {:db (-> (panel-set db :changing-plan? false)
                          (panel-set :error-message nil)
                          (panel-set :redirecting? true)
                          (load-user-current-plan plan))
                  :dispatch-n (list
                                [:load-url nav-url]
                                [:ga-event "stripe" "plan_subscribe" (:nickname plan)])})))
  :on-error (fn [{:keys [db error]} _ _]
              (let [msg (if (= (:type error) "invalid_request_error")
                          "You must enter a valid payment method before subscribing to this plan"
                          (:message error))]
                {:db (-> (panel-set db :changing-plan? false)
                         (panel-set :error-message msg)
                         (stripe/panel-set :need-card? true))})))

(defn- ToggleInterval []
  (let [new-plan (r/cursor state [:new-plan])
        available-plans (subscribe [:user/available-plans])]
    (r/create-class
     {:render (fn [_]
                [SegmentGroup {:horizontal true
                               :compact true
                               :style {:width "46%"}}
                 [Segment (cond-> {}
                            (= (:interval @new-plan) "month")
                            (merge {:tertiary true}))
                  [Radio {:label "Pay Monthly"
                          :value "monthly"
                          :checked (= (:interval @new-plan) "month")
                          :on-change
                          (fn [_]
                            (reset! new-plan
                                    (medley/find-first #(= (:interval %) "month")
                                                       @available-plans)))}]]
                 [Segment (cond-> {}
                            (= (:interval @new-plan) "year")
                            (merge
                             {:tertiary true}))
                  [Radio {:label "Pay Yearly"
                          :checked (= (:interval @new-plan) "year")
                          :on-change (fn [_]
                                       (reset! new-plan
                                               (medley/find-first #(= (:interval %) "year")
                                                                  @available-plans)))}]]])})))

(defn- ProPlanPrice [plan]
  [:p (str "Pro Plan ("
           (str "$" (util/cents->dollars
                     (:amount @plan)) " / " (:interval @plan))
           ")")])

(defn price-summary [member-count tiers]
  (let [base (->> tiers (map :flat_amount) (filter int?) sum)
        per-user (->> tiers (map :unit_amount) (filter int?) sum)]
    {:base base :per-user per-user
     :up-to (->> tiers (map :up_to) (filter int?) first)
     :monthly-bill (+ base (* (max 0 (- member-count 5)) per-user))}))

(defn Unlimited [{:keys [tiers interval] :as _plan}]
  [Segment
   (if-not tiers
     [Loader {:active true :inline "centered"}]
     [Grid {:stackable true}
      [Row
       [Column {:width 10}
        [:b "Premium Plan"]
        [pricing/TeamProBenefits]]
       [Column {:width 6 :align "right"}
        (let [{:keys [base per-user up-to]} (price-summary 0 tiers)]
          [:div
           [Row [:h3 (str "$" (util/cents->dollars base) " / " interval)]]
           [Row [:h3 (str "up to " up-to " members")]]
           [:br]
           [Row [:h3 "$ " (util/cents->dollars per-user) " / " interval]]
           [Row [:h3 "per additional member"]]])]]])])

(defn BasicPlan [{:keys [amount interval] :as _plan}]
  [Segment
   (if-not amount
     [Loader {:active true :inline "centered"}]
     [Grid {:stackable true}
      [Row
       [Column {:width 8}
        [:b "Basic"]
        [ListUI [FreeBenefits]]]
       [Column {:width 8 :align "right"}
        [Row [:h3 (str "$" (util/cents->dollars amount) " / " interval)]]]]])])

(defn TogglePlanButton [{:keys [on-click class loading disabled] :as attrs} text]
  [Button (merge {:color "green"} attrs) text])

(defn- DowngradePlan []
  (let [self-id @(subscribe [:self/user-id])
        error-message (r/cursor state [:error-message])
        available-plans (subscribe [:user/available-plans])
        changing-plan? (r/cursor state [:changing-plan?])
        current-plan (subscribe [:user/current-plan self-id])
        new-plan (r/cursor state [:new-plan])]
    (r/create-class
     {:reagent-render
      (fn [_]
        (when (empty? @new-plan)
          (reset! new-plan (medley/find-first #(= (:nickname %) "Basic") @available-plans)))
        [:div
         [:h1 "Unsubscribe from your plan"]
         [Grid
          [Row
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe from"]
                        [Unlimited @current-plan]]]]
            [Grid [Row [Column
                        [:h3 "New Plan"]
                        [BasicPlan @new-plan]
                        [:a {:href (str "/user/" self-id "/billing")}
                         "Back to user settings"]]]]]
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe Summary"]
                        [ListUI {:divided true}
                         [:h4 "New Monthly Bill"]
                         [ListItem [:p "Basic plan ($0 / month)"]]
                         [:div {:style {:margin-top "1em" :width "100%"}}
                          [TogglePlanButton {:disabled @changing-plan?
                                             :on-click #(do (reset! changing-plan? true)
                                                            (run-action :user/subscribe-plan
                                                                        self-id @new-plan))
                                             :class "unsubscribe-plan"
                                             :loading @changing-plan?}
                           "Unsubscribe"]]
                         (when @error-message
                           [S/Message {:negative true}
                            [S/MessageHeader "Change Plan Error"]
                            [:p @error-message]])]]]]]]]])
      :get-initial-state (fn [_this]
                           (reset! changing-plan? false)
                           (reset! error-message nil))})))

(defn- UpgradePlan []
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])
        mobile? (util/mobile?)
        new-plan (r/cursor state [:new-plan])
        self-id @(subscribe [:self/user-id])
        default-source (subscribe [:user/default-source])
        show-payment-form? (r/atom false)
        changing-interval? (uri-utils/getParamValue @active-route "changing-interval")]
    (fn [available-plans]
      (when (and (not (nil? @available-plans))
                 (nil? @new-plan ))
        (reset! new-plan (medley/find-first #(= (:nickname %) plans-info/unlimited-user) @available-plans)))
      (if (empty? @available-plans)
        [Loader {:active true
                 :inline "centered"}]
        [:div
         (when-not (and (not mobile?)
                        changing-interval?)
           [:h1 "Upgrade from Basic to Premium"])
         [Grid {:stackable true :columns 2 :class "upgrade-plan"}
          [Column
           [Grid [Row [Column
                       (when-not changing-interval?
                         [:h3 "UPGRADING TO"])
                       [Unlimited @new-plan]
                       (when-not mobile?
                         [:a {:href (str "/user/" self-id "/billing")}
                          "Back to user settings"])]]]]
          [Column
           (let [no-default? (empty? @default-source)]
             [Grid
              [Row
               [Column
                (if changing-interval? [:h3 "Billing Summary"] [:h3 "Upgrade Summary"])
                [ToggleInterval]
                [:p {:style {:color "green"}} "Pay yearly and get the first month free!"]
                [ListUI {:divided true}
                 [:h4 "New Monthly Bill"]
                 [ListItem [ProPlanPrice new-plan]]
                 [:h4 "Billing Information"]
                 [ListItem (when (not no-default?)
                             [DefaultSource @default-source])]
                 (when (and @show-payment-form? (not no-default?))
                   [Button {:on-click #(swap! show-payment-form? not)
                            :positive true
                            :style {:margin-bottom "1em" :margin-top "1em"}}
                    "Use current payment source"])
                 (when (empty? @error-message)
                   (if (or no-default? @show-payment-form?)
                     [StripeCardInfo {:add-payment-fn
                                      (fn [payload]
                                        (reset! show-payment-form? false)
                                        (run-action :stripe/add-payment-user self-id payload))}]
                     [:a.payment-method
                      {:class (if no-default? "add-method" "change-method")
                       :style {:cursor "pointer"}
                       :on-click (util/wrap-prevent-default
                                  #(reset! show-payment-form? true))}
                      "Change payment method"]))
                 (when-not (or no-default?
                               @show-payment-form?)
                   [:div {:style {:margin-top "1em" :width "100%"}}
                    [TogglePlanButton {:disabled (or no-default? @changing-plan?)
                                       :on-click #(do (reset! changing-plan? true)
                                                      (run-action :user/subscribe-plan
                                                                  self-id @new-plan))
                                       :class "upgrade-plan"
                                       :loading @changing-plan?}
                     "Upgrade Plan"]
                    ;; https://stripe.com/docs/payments/cards/reusing-cards#mandates
                    [:p {:style {:margin-top "1em"}}
                     "By clicking 'Upgrade Plan' you authorize Insilica LLC to send instructions to the financial institution that issued your card to take payments from your card account in accordance with the above terms."]])
                 (when @error-message
                   [S/Message {:negative true}
                    [S/MessageHeader "Change Plan Error"]
                    [:p @error-message]])]]]])]]]))))

(defn- on-mount-user-plans []
  (let [self-id @(subscribe [:self/user-id])]
    (dispatch [::set :changing-plan? nil])
    (dispatch [::set :error-message nil])
    (dispatch [:set-active-panel [:plans]])
    (dispatch [::set :redirecting? false])
    (when self-id
      (dispatch [:data/load [:user/current-plan self-id]])
      (dispatch [:data/load [:user/default-source self-id]])
      (dispatch [:data/load [:user/available-plans self-id]]))))

(defn- Panel []
  (on-mount-user-plans)
  (fn []
    (when @(subscribe [:self/user-id])
      (let [current-plan @(subscribe [:user/current-plan])
            redirecting? (r/cursor state [:redirecting?])
            changing-interval? (uri-utils/getParamValue @active-route "changing-interval")]
        (cond
          @redirecting?
          [:div [:h3 "Redirecting"]
           [Loader {:active true
                    :inline "centered"}]]
          changing-interval?
          [UpgradePlan (subscribe [:user/available-plans])]
          (or (nil? current-plan) (= (:nickname current-plan) "Basic"))
          [UpgradePlan (subscribe [:user/available-plans])]
          (plans-info/pro? (:nickname current-plan))
          [DowngradePlan]
          :else
          [Loader {:active true :inline "centered"}])))))

(def-panel :uri "/user/plans" :panel panel
  :on-route (on-mount-user-plans)
  :content [Panel]
  :require-login true)
