(ns sysrev.views.panels.org.plans
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v reg-event-fx]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.user.plans :refer [UpgradePlan DowngradePlan]]
            [sysrev.views.semantic :refer [Message MessageHeader]]
            [sysrev.shared.util :refer [parse-integer]])
  (:require-macros [sysrev.macros :refer [with-loader setup-panel-state]]))

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
  :process (fn [{:keys [db]} [org-id] {:keys [plan] :as result}]
             {:db (load-org-current-plan db org-id plan)}))

(reg-sub ::state (fn [db _] (panel-get db)))
(reg-sub ::org (fn [db [_ org-id]] (panel-get db org-id)))

(reg-sub :org/current-plan
         (fn [[_ org-id]] (subscribe [::org org-id]))
         (fn [org] (:current-plan org)))

(reg-sub :org/on-subscribe-nav-to-url
         (fn [[_ org-id]] (subscribe [::org org-id]))
         (fn [org] (:on-subscribe-nav-to-url org)))

(reg-event-db :org/set-on-subscribe-nav-to-url!
              (fn [db [_ org-id url]]
                (panel-set db [org-id :on-subscribe-nav-to-url] url)))

(def-action :org/subscribe-plan
  :uri (fn [org-id plan-name] (str "/api/org/" org-id "/stripe/subscribe-plan"))
  :content (fn [org-id plan-name]
             #_ (js/console.log ":org/subscribe-plan running " (pr-str [org-id plan-name]))
             {:plan-name plan-name})
  :process (fn [{:keys [db]} [org-id _] {:keys [stripe-body plan] :as result}]
             #_ (js/console.log ":org/subscribe-plan result = " (pr-str result))
             (if (:created stripe-body)
               (let [nav-url (panel-get db [org-id :on-subscribe-nav-to-url])]
                 {:db (-> (panel-set db :changing-plan? false)
                          (panel-set :error-message nil)
                          (load-org-current-plan org-id plan))
                  :dispatch-n (list
                               #_ [:data/load [:org/current-plan org-id]]
                               ;; need to download all projects associated with the user
                               ;; to update [:project/subscription-lapsed?] for MakePublic
                               [:project/fetch-all-projects]
                               [:nav-scroll-top nav-url])})))
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
                      :member-count @(subscribe [:org/member-count org-id])}}]
      (condp = (:name current-plan)
        "Basic"
        [UpgradePlan
         (merge plan-args
                {:default-source default-source
                 :on-upgrade #(dispatch [:action [:org/subscribe-plan org-id "Unlimited_Org"]])
                 :on-add-payment-method
                 #(do (dispatch [:stripe/set-calling-route! (str "/org/" org-id "/plans")])
                      (nav-scroll-top (str "/org/" org-id "/payment")))})]
        "Unlimited_Org"
        [DowngradePlan
         (merge plan-args
                {:on-downgrade #(dispatch [:action [:org/subscribe-plan org-id "Basic"]])})]
        [Message {:negative true}
         [MessageHeader "Organization Plans Error"]
         [:div.content
          [:p (str "Plan (" (:name current-plan) ") is not recognized for org-id: " org-id)]
          [:p (str "Active Route: " @active-route)]]]))))

(defn OrgPlans [{:keys [org-id]}]
  (r/create-class
   {:reagent-render (fn [{:keys [org-id]}] [OrgPlansImpl org-id])
    :component-will-mount
    (fn [this]
      (dispatch [::set :error-message nil])
      (dispatch [::set :changing-plan? nil])
      (dispatch [:data/load [:self-orgs]])
      (dispatch [:data/load [:org/default-source org-id]])
      (dispatch [:data/load [:org/current-plan org-id]]))
    :component-will-receive-props
    (fn [this [{:keys [org-id]}]]
      (dispatch [:data/load [:org/default-source org-id]])
      (dispatch [:data/load [:org/current-plan org-id]]))}))
