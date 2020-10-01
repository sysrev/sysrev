(ns sysrev.views.panels.org.plans
  (:require [goog.uri.utils :as uri-utils]
            [medley.core :as medley :refer [find-first]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.loading :as loading]
            [sysrev.stripe :as stripe :refer [StripeCardInfo]]
            [sysrev.views.panels.org.main :as org]
            [sysrev.views.semantic :refer [SegmentGroup Segment Radio Loader Grid Column Row
                                           ListUI ListItem Message MessageHeader Button]]
            [sysrev.views.panels.user.billing :as user-billing]
            [sysrev.views.panels.user.plans :as user-plans]
            [sysrev.views.panels.pricing :as pricing]
            [sysrev.util :as util :refer [sum]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org-plans] {:state-var state
                                       :get-fn panel-get
                                       :set-fn panel-set
                                       :get-sub ::get
                                       :set-event ::set})

(def-data :org/available-plans
  :loaded? (fn [db] (-> (get-in db [:data :plans])
                        (contains? :available-plans)))
  :uri (constantly "/api/org/available-plans")
  :process (fn [{:keys [db]} _ {:keys [plans]}]
             {:db (assoc-in db [:data :plans :available-plans] plans)}))

(reg-sub :org/available-plans #(get-in % [:data :plans :available-plans]))

(defn- set-org-current-plan [db org-id plan]
  (assoc-in db [:data :orgs org-id :current-plan] plan))

(def-data :org/current-plan
  :loaded? (fn [db org-id] (-> (get-in db [:data :orgs org-id])
                               (contains? :current-plan)))
  :uri (fn [org-id] (str "/api/org/" org-id "/stripe/current-plan"))
  :process (fn [{:keys [db]} [org-id] {:keys [plan]}]
             {:db (set-org-current-plan db org-id plan)}))

(reg-sub :org/current-plan
         (fn [db [_ org-id]] (get-in db [:data :orgs org-id :current-plan])))

(def-action :org/subscribe-plan
  :uri (fn [org-id _] (str "/api/org/" org-id "/stripe/subscribe-plan"))
  :content (fn [_ plan] {:plan plan})
  :process (fn [{:keys [db]} [org-id _] {:keys [stripe-body plan]}]
             (when (:created stripe-body)
               (let [nav-url (-> (or (:on_subscribe_uri (util/get-url-params))
                                     (str "/org/" org-id "/billing")))]
                 {:db (-> (panel-set db :error-message nil)
                          (panel-set    :redirecting? true)
                          (set-org-current-plan org-id plan))
                  :dispatch [:load-url nav-url]})))
  :on-error (fn [{:keys [db error]} _ _]
              (let [msg (if (= (:type error) "invalid_request_error")
                          "You must enter a valid payment method before subscribing to this plan"
                          (:message error))]
                {:db (-> (panel-set db :error-message msg)
                         (stripe/panel-set :need-card? true))})))

(defn price-summary [member-count tiers]
  (let [base (->> tiers (map :flat_amount) (filter int?) sum)
        per-user (->> tiers (map :unit_amount) (filter int?) sum)]
    {:base base :per-user per-user
     :up-to (->> tiers (map :up_to) (filter int?) first)
     :monthly-bill (+ base (* (max 0 (- member-count 5)) per-user))}))

(defn ToggleInterval [{:keys [style new-plan available-plans set-plan]}]
  [SegmentGroup {:horizontal true :compact true
                 :style (merge {:width "46%"} style)}
   [Segment (into {} (cond (= (:interval new-plan) "month")
                           {:tertiary true}))
    [Radio {:label "Pay Monthly"
            :value "monthly"
            :checked (= (:interval new-plan) "month")
            :on-change #(set-plan (find-first (comp (partial = "month") :interval)
                                              available-plans))}]]
   [Segment (into {} (cond (= (:interval new-plan) "year")
                           {:tertiary true}))
    [Radio {:label "Pay Yearly"
            :checked (= (:interval new-plan) "year")
            :on-change #(set-plan (find-first (comp (partial = "year") :interval)
                                              available-plans))}]]])

(defn Unlimited [{:keys [tiers interval] :as _plan}]
  [Segment
   (if-not tiers
     [Loader {:active true :inline "centered"}]
     [Grid {:stackable true}
      [Row
       [Column {:width 10}
        [:b "Team Pro Plan"]
        [pricing/TeamProBenefits]]
       [Column {:width 6 :align "right"}
        (let [{:keys [base per-user up-to]} (price-summary 0 tiers)]
          [:div
           [Row [:h3 (str "$" (util/cents->dollars base) " / " interval)]]
           [Row [:h3 (str "up to " up-to " members")]]
           [:br]
           [Row [:h3 "$ " (util/cents->dollars per-user) " / " interval]]
           [Row [:h3 "per additional member"]]])]]])])

(defn DowngradePlan [_org-id]
  (dispatch [::set :error-message nil])
  (fn [org-id]
    (let [running? (loading/any-action-running? :only :org/subscribe-plan)
          available-plans @(subscribe [:org/available-plans])
          new-plan (or @(subscribe [::get :new-plan])
                       (find-first #(= (:nickname %) "Basic") available-plans))
          current-plan @(subscribe [:org/current-plan org-id])]
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
                      [user-plans/BasicPlan new-plan]
                      [:a {:href (str "/org/" org-id "/billing")}
                       "Back to org settings"]]]]]
         [Column {:width 8}
          [Grid [Row [Column
                      [:h3 "Unsubscribe Summary"]
                      [ListUI {:divided true}
                       [:h4 "New Monthly Bill"]
                       [ListItem [:p "Basic plan ($0 / month)"]]
                       [:div {:style {:margin-top "1em" :width "100%"}}
                        [user-plans/TogglePlanButton
                         {:class "unsubscribe-plan"
                          :disabled running? :loading running?
                          :on-click #(run-action :org/subscribe-plan org-id new-plan)}
                         "Unsubscribe"]]
                       (when-let [msg (subscribe [::get :error-message])]
                         [Message {:negative true}
                          [MessageHeader "Change Plan Error"]
                          [:p msg]])]]]]]]]])))

(defn TeamProPlanPrice [plan]
  [:p (str "Team Pro Plan ("
           (str "$" (-> plan
                        :tiers
                        ;; this needs to be changed to actually
                        ;; include member count of group
                        ((partial price-summary 0))
                        :monthly-bill
                        util/cents->dollars)
                " / " (:interval plan))
           ")")])

(defn UpgradePlan [org-id]
  (let [mobile? (util/mobile?)
        error-message (r/cursor state [:error-message])
        show-payment-form? (r/atom false)
        changing-interval? (uri-utils/getParamValue @active-route "changing-interval")
        available-plans (subscribe [:org/available-plans])
        default-source (subscribe [:org/default-source org-id])]
    (fn [org-id]
      (let [new-plan (or @(subscribe [::get :new-plan])
                         (find-first #(= (:nickname %) "Unlimited_Org") @available-plans))
            running? (loading/any-action-running? :only :org/subscribe-plan)]
        (if (empty? @available-plans)
          [Loader {:active true :inline "centered"}]
          [:div
           (when-not (and (not mobile?) changing-interval?)
             [:h1 "Upgrade from Basic to Team Pro"])
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
                  [ToggleInterval {:new-plan new-plan :available-plans @available-plans
                                   :set-plan #(dispatch [::set :new-plan %])}]
                  [:p {:style {:color "green"}} "Pay yearly and get the first month free!"]
                  [ListUI {:divided true}
                   [:h4 "New Monthly Bill"]
                   [ListItem [TeamProPlanPrice new-plan]]
                   [:h4 "Billing Information"]
                   [ListItem
                    (when (not no-default?)
                      [user-billing/DefaultSource @default-source])]
                   (when (and @show-payment-form? (not no-default?))
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
                                          (run-action :stripe/add-payment-org org-id payload))}]
                       [:a.payment-method
                        {:class (if no-default? "add-method" "change-method")
                         :style {:cursor "pointer"}
                         :on-click (util/wrap-prevent-default
                                    #(reset! show-payment-form? true))}
                        "Change payment method"]))
                   (when-not (or no-default? @show-payment-form?)
                     [:div {:style {:margin-top "1em" :width "100%"}}
                      [user-plans/TogglePlanButton
                       {:disabled (or no-default? running?)
                        :on-click #(run-action :org/subscribe-plan org-id new-plan)
                        :class "upgrade-plan"
                        :loading running?}
                       "Upgrade Plan"]
                      ;; https://stripe.com/docs/payments/cards/reusing-cards#mandates
                      [:p {:style {:margin-top "1em"}}
                       "By clicking 'Upgrade Plan' you authorize Insilica LLC to send instructions to the financial institution that issued your card to take payments from your card account in accordance with the above terms."]])
                   (when @error-message
                     [Message {:negative true}
                      [MessageHeader "Change Plan Error"]
                      [:p @error-message]])]]]])]]])))))

(defn- OrgPlansContent [org-id]
  (let [current-plan @(subscribe [:org/current-plan org-id])
        redirecting? (r/cursor state [:redirecting?])
        changing-interval? (uri-utils/getParamValue @active-route "changing-interval")]
    (cond @redirecting?
          [:div [:h3 "Redirecting"]
           [Loader {:active true :inline "centered"}]]
          changing-interval?
          [UpgradePlan org-id]
          :else (case (:nickname current-plan)
                  "Basic"
                  [UpgradePlan org-id]
                  ("Unlimited_Org" "Unlimited_Org_Annual")
                  [DowngradePlan org-id]
                  [Loader {:active true :inline "centered"}]))))

(def-panel {:uri "/org/:org-id/plans" :params [org-id]
            :on-route (let [org-id (util/parse-integer org-id)]
                        (org/on-navigate-org org-id panel)
                        (dispatch [:reload [:org/default-source org-id]]))
            :panel panel
            :content (when-let [org-id @(subscribe [::org/org-id])]
                       (with-loader [[:org/current-plan org-id]
                                     [:org/available-plans org-id]
                                     [:org/default-source org-id]] {}
                         [OrgPlansContent org-id]))
            :require-login true})
