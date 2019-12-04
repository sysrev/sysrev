(ns sysrev.views.panels.org.plans
  (:require [reagent.core :as r]
            [reagent.ratom :refer [track!]]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.panels.user.plans :refer [UpgradePlan DowngradePlan]]
            [sysrev.views.semantic :refer [Message MessageHeader Loader]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:org-plans] {:state-var state
                                       :get-fn panel-get
                                       :set-fn panel-set
                                       :get-sub ::get
                                       :set-event ::set})

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
  :content (fn [_ plan-name] {:plan-name plan-name})
  :process (fn [{:keys [db]} [org-id _] {:keys [stripe-body plan]}]
             (when (:created stripe-body)
               (let [nav-url (-> (or (:on_subscribe_uri (nav/get-url-params))
                                     (str "/org/" org-id "/billing")))]
                 {:db (-> (panel-set db :changing-plan? false)
                          (panel-set :error-message nil)
                          (load-org-current-plan org-id plan))
                  :dispatch [:nav-reload nav-url]})))
  :on-error (fn [{:keys [db error]} _ _]
              (let [msg (if (= (:type error) "invalid_request_error")
                          "You must enter a valid payment method before subscribing to this plan"
                          (:message error))]
                {:db (-> (panel-set db :changing-plan? false)
                         (panel-set :error-message msg)
                         (stripe/panel-set :need-card? true))})))

(defn- OrgPlansImpl [org-id]
  (when org-id
    (let [current-plan @(subscribe [:org/current-plan org-id])
          default-source @(subscribe [:org/default-source org-id])
          plan-args {:state state
                     :billing-settings-uri (str "/org/" org-id "/billing")
                     :unlimited-plan-name "Team Pro Plan"
                     :unlimited-plan-price
                     {:tiers
                      [{:flat_amount 3000, :unit_amount nil, :up_to 5}
                       {:flat_amount nil, :unit_amount 1000, :up_to nil}]
                      :member-count @(subscribe [:org/member-count org-id])}}
          loading? (track! loading/any-loading?)]
      (condp = (:name current-plan)
        "Basic"
        [UpgradePlan
         (merge plan-args
                {:default-source default-source
                 :on-upgrade #(dispatch [:action [:org/subscribe-plan org-id "Unlimited_Org"]])
                 :on-add-payment-method
                 (fn []
                   (nav-scroll-top
                    (str "/org/" org-id "/payment")
                    :params
                    (assoc (nav/get-url-params)
                           :redirect_uri
                           (if-let [current-redirect-uri (:redirect_uri (nav/get-url-params))]
                             current-redirect-uri
                     (str "/org/" org-id "/plans")))))})]
        "Unlimited_Org"
        [DowngradePlan
         (merge plan-args
                {:on-downgrade #(dispatch [:action [:org/subscribe-plan org-id "Basic"]])})]
        (not @loading?)
        [Message {:negative true}
         [MessageHeader "Organization Plans Error"]
         [:div.content
          [:p (str "Plan (" (:name current-plan) ") is not recognized for org-id: " org-id)]
          [:p (str "Active Route: " @active-route)]]]
        ;; default
        [Loader {:active true
                 :inline "centered"}]))))

(defn OrgPlans [{:keys [org-id]}]
  (r/create-class
   {:reagent-render (fn [{:keys [org-id]}] [OrgPlansImpl org-id])
    :component-will-mount
    (fn [_this]
      (dispatch [::set :error-message nil])
      (dispatch [::set :changing-plan? nil])
      (dispatch [:data/load [:self-orgs]])
      (dispatch [:data/load [:org/default-source org-id]])
      (dispatch [:data/load [:org/current-plan org-id]]))
    :component-will-receive-props
    (fn [_this [{:keys [org-id]}]]
      (dispatch [:data/load [:org/default-source org-id]])
      (dispatch [:data/load [:org/current-plan org-id]]))}))
