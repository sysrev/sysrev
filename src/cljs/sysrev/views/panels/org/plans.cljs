(ns sysrev.views.panels.org.plans
  (:require [goog.uri.utils :as uri-utils]
            [medley.core :as medley]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.macros :refer-macros [setup-panel-state]]
            [sysrev.nav :as nav]
            [sysrev.stripe :as stripe :refer [StripeCardInfo]]
            [sysrev.util :as util]
            [sysrev.views.semantic :refer [SegmentGroup Segment Radio Loader Grid Column Row ListUI ListItem Message MessageHeader Button]]
            [sysrev.views.panels.user.billing :refer [DefaultSource]]
            [sysrev.views.panels.user.plans :refer [TogglePlanButton BasicPlan]]
            [sysrev.views.panels.pricing :refer [TeamProBenefits]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:org-plans] {:state-var state
                                       :get-fn panel-get
                                       :set-fn panel-set
                                       :get-sub ::get
                                       :set-event ::set})

(def-data :org/available-plans
  :loaded? (fn [db] (-> (get-in db [:data :plans :available-plans])))
  :uri (fn [] (str "/api/org/available-plans"))
  :process (fn [{:keys [db]} _ {:keys [plans]}]
             {:db (assoc-in db [:data :plans :available-plans]
                            plans)}))

(reg-sub :org/available-plans #(get-in % [:data :plans :available-plans]))

(defn- load-org-current-plan [db org-id plan]
  (panel-set db [org-id :current-plan] plan))

(def-data :org/current-plan
  :loaded? (fn [db org-id] (-> (panel-get db org-id)
                               (contains? :current-plan)))
  :uri (fn [org-id] (str "/api/org/" org-id "/stripe/current-plan"))
  :process (fn [{:keys [db]} [org-id] {:keys [plan]}]
             {:db (load-org-current-plan db org-id plan)}))

(reg-sub ::state (fn [db _] (panel-get db)))
(reg-sub ::org (fn [db [_ org-id]] (panel-get db org-id)))

(reg-sub :org/current-plan
         (fn [[_ org-id]] (subscribe [::org org-id]))
         #(:current-plan %))

(def-action :org/subscribe-plan
  :uri (fn [org-id _] (str "/api/org/" org-id "/stripe/subscribe-plan"))
  :content (fn [_ plan] {:plan plan})
  :process (fn [{:keys [db]} [org-id _] {:keys [stripe-body plan]}]
             (when (:created stripe-body)
               (let [nav-url (-> (or (:on_subscribe_uri (nav/get-url-params))
                                     (str "/org/" org-id "/billing")))]
                 {:db (-> (panel-set db :changing-plan? false)
                          (panel-set :error-message nil)
                          (panel-set :redirecting? true)
                          (load-org-current-plan org-id plan))
                  :dispatch [:nav-reload nav-url]})))
  :on-error (fn [{:keys [db error]} _ _]
              (let [msg (if (= (:type error) "invalid_request_error")
                          "You must enter a valid payment method before subscribing to this plan"
                          (:message error))]
                {:db (-> (panel-set db :changing-plan? false)
                         (panel-set :error-message msg)
                         (stripe/panel-set :need-card? true))})))

(defn price-summary
  [member-count tiers]
  (let [base (->> tiers (map :flat_amount) (filter int?) (apply +))
        per-user (->> tiers (map :unit_amount) (filter int?) (apply +))
        up-to (->> tiers (map :up_to) (filter int?) first)
        monthly-bill (+ base (* (max 0 (- member-count 5)) per-user))]
    {:base base
     :per-user per-user
     :up-to up-to
     :monthly-bill monthly-bill}))

(defn ToggleInterval
  "new-plan is an atom, not a value"
  [{:keys [style new-plan available-plans]}]
  (r/create-class
   {:render (fn [_]
              [SegmentGroup {:horizontal true
                             :compact true
                             :style (merge {:width "46%"}
                                           style)}
               [Segment (cond-> {}
                          (= (:interval @new-plan) "month")
                          (merge
                           {:tertiary true}))
                [Radio {:label "Pay Monthly"
                        :value "monthly"
                        :checked (= (:interval @new-plan) "month")
                        :on-change
                        (fn [_]
                          (reset! new-plan
                                  (medley/find-first #(= (:interval %) "month")
                                                     ;;@available-plans
                                                     available-plans
                                                     )))}]]
               [Segment (cond-> {}
                          (= (:interval @new-plan) "year")
                          (merge
                           {:tertiary true}))
                [Radio {:label "Pay Yearly"
                        :checked (= (:interval @new-plan) "year")
                        :on-change (fn [_]
                                     (reset! new-plan
                                             (medley/find-first #(= (:interval %) "year")
                                                                ;; @available-plans
                                                                available-plans)))}]]])}))
(defn Unlimited
  [plan]
  (let [{:keys [tiers interval]} @plan]
    [Segment
     (if-not tiers
       [Loader {:active true
                :inline "centered"}]
       [Grid {:stackable true}
        [Row
         [Column {:width 10}
          [:b "Team Pro Plan"]
          [TeamProBenefits]]
         [Column {:width 6 :align "right"}
          (let [{:keys [base per-user up-to]} (price-summary 0 tiers)]
            [:div
             [Row [:h3 (str "$" (util/cents->dollars base) " / " interval)]]
             [Row [:h3 (str "up to " up-to " members")]]
             [:br]
             [Row [:h3 "$ " (util/cents->dollars per-user) " / " interval]]
             [Row [:h3 "per additional member"]]])]]])]))

(defn DowngradePlan [org-id]
  (let [error-message (r/cursor state [:error-message])
        available-plans (subscribe [:org/available-plans])
        changing-plan? (r/cursor state [:changing-plan?])
        current-plan (subscribe [:org/current-plan org-id])
        new-plan (r/cursor state [:new-plan])]
    (r/create-class
     {:reagent-render
      (fn [_]
        (when (empty? @new-plan)
          (reset! new-plan (medley/find-first #(= (:nickname %) "Basic") @available-plans)))
        [:div
         [:h1 "Unsubscribe your Team"]
         [:h2 "Team: " @(subscribe [:org/name org-id])]
         [Grid
          [Row
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe from"]
                        [Unlimited current-plan]]]]
            [Grid [Row [Column
                        [:h3 "New Plan"]
                        [BasicPlan @new-plan]
                        [:a {:href (str "/org/" org-id "/billing")}
                         "Back to org settings"]]]]]
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe Summary"]
                        [ListUI {:divided true}
                         [:h4 "New Monthly Bill"]
                         [ListItem [:p "Basic plan ($0 / month)"]]
                         [:div {:style {:margin-top "1em" :width "100%"}}
                          [TogglePlanButton {:disabled @changing-plan?
                                             :on-click #(do (reset! changing-plan? true)
                                                            (dispatch [:action [:org/subscribe-plan org-id @new-plan]]))
                                             :class "unsubscribe-plan"
                                             :loading @changing-plan?}
                           "Unsubscribe"]]
                         (when @error-message
                           [Message {:negative true}
                            [MessageHeader "Change Plan Error"]
                            [:p @error-message]])]]]]]]]])
      :get-initial-state (fn [_this]
                           (reset! changing-plan? false)
                           (reset! error-message nil))})))

(defn TeamProPlanPrice [plan]
  [:p (str "Team Pro Plan ("
           (str "$" (-> @plan
                        :tiers
                        ;; this needs to be changed to actually
                        ;; include member count of group
                        ((partial price-summary 0))
                        :monthly-bill
                        util/cents->dollars)
                " / " (:interval @plan))
           ")")])

(defn UpgradePlan [org-id]
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])
        mobile? (util/mobile?)
        new-plan (r/cursor state [:new-plan])
        default-source (subscribe [:org/default-source org-id])
        show-payment-form? (r/atom false)
        changing-interval? (uri-utils/getParamValue @active-route "changing-interval")]
    (fn [org-id available-plans]
      (when-not (nil? @available-plans)
        (reset! new-plan (medley/find-first #(= (:nickname %) "Unlimited_Org") @available-plans)))
      (if (empty? @available-plans)
        [Loader {:active true
                 :inline "centered"}]
        [:div
         (when-not (and (not mobile?)
                        changing-interval?) [:h1 "Upgrade from Basic to Team Pro"])
         [:h2 "Team: " @(subscribe [:org/name org-id])]
         [Grid {:stackable true :columns 2 :class "upgrade-plan"}
          [Column
           [Grid [Row [Column
                       (when-not changing-interval?
                         [:h3 "UPGRADING TO"])
                       [Unlimited new-plan]
                       (when-not mobile?
                         [:a {:href (str "/org/" org-id "/billing")}
                          "Back to org settings"])]]]]
          [Column
           (let [no-default? (empty? @default-source)]
             [Grid
              [Row
               [Column
                (if-not changing-interval? [:h3 "Billing Summary"] [:h3 "Upgrade Summary"])
                [ToggleInterval {:new-plan new-plan :available-plans @available-plans}]
                [:p {:style {:color "green"}} "Pay yearly and get the first month free!"]
                [ListUI {:divided true}
                 [:h4 "New Monthly Bill"]
                 [ListItem [TeamProPlanPrice new-plan]]
                 [:h4 "Billing Information"]
                 [ListItem
                  (when (not no-default?)
                    [DefaultSource {:default-source @default-source}])]
                 (when (and @show-payment-form?
                            (not no-default?))
                   [Button {:on-click #(swap! show-payment-form? not)
                            :positive true
                            :style {:margin-bottom "1em"
                                    :margin-top "1em"}}
                    "Use current payment source"])
                 (when (empty? @error-message)
                   (if (or no-default? @show-payment-form?)
                     [StripeCardInfo {:add-payment-fn
                                      (fn [payload]
                                        (reset! show-payment-form? false)
                                        (dispatch [:action [:stripe/add-payment-org org-id payload]]))}]
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
                                                      (dispatch [:action [:org/subscribe-plan org-id @new-plan]]))
                                       :class "upgrade-plan"
                                       :loading @changing-plan?}
                     "Upgrade Plan"]
                    ;; https://stripe.com/docs/payments/cards/reusing-cards#mandates
                    [:p {:style {:margin-top "1em"}}
                     "By clicking 'Upgrade Plan' you authorize Insilica LLC to send instructions to the financial institution that issued your card to take payments from your card account in accordance with the above terms."]])
                 (when @error-message
                   [Message {:negative true}
                    [MessageHeader "Change Plan Error"]
                    [:p @error-message]])]]]])]]]))))

(defn- OrgPlansContent [org-id]
  (when org-id
    (let [current-plan @(subscribe [:org/current-plan org-id])
          redirecting? (r/cursor state [:redirecting?])
          changing-interval? (uri-utils/getParamValue @active-route "changing-interval")]
      (cond
        @redirecting?
        [:div [:h3 "Redirecting"]
         [Loader {:active true
                  :inline "centered"}]]
        changing-interval?
        [UpgradePlan org-id (subscribe [:org/available-plans])]
        (= (:nickname current-plan) "Basic")
        [UpgradePlan org-id (subscribe [:org/available-plans])]
        (contains? #{"Unlimited_Org" "Unlimited_Org_Annual"} (:nickname current-plan))
        [DowngradePlan org-id]
        :else
        [Loader {:active true
                 :inline "centered"}]))))

(defn OrgPlans [{:keys [org-id]}]
  (r/create-class
   {:reagent-render (fn [{:keys [org-id]}] [OrgPlansContent org-id])
    :component-did-mount
    (fn [_this]
      (dispatch [::set :error-message nil])
      (dispatch [::set :changing-plan? nil])
      (dispatch [::set :redirecting? false])
      (dispatch [:data/load [:self-orgs]])
      (dispatch [:data/load [:org/default-source org-id]])
      (dispatch [:data/load [:org/current-plan org-id]])
      (dispatch [:data/load [:org/available-plans]]))
    :should-component-update
    (fn [_this _old-argv [{:keys [org-id]}]]
      (dispatch [:data/load [:org/default-source org-id]])
      (dispatch [:data/load [:org/current-plan org-id]]))}))
