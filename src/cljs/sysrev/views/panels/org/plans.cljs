(ns sysrev.views.panels.org.plans
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.user.plans :refer [UpgradePlan DowngradePlan]]
            [sysrev.views.semantic :refer [Message MessageHeader]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:org-plans])

(def initial-state {:selected-plan nil
                    :changing-plan? false
                    :error-message nil})

(def state (r/cursor app-db [:state :panels panel]))

(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(def-data :org-current-plan
  :loaded? (fn [db]
             (contains? @state :current-plan))
  :uri (fn [org-id] (str "/api/org/" org-id "/stripe/current-plan"))
  :prereqs (fn []
             [[:identity]])
  :process (fn [_ _ result]
             (ensure-state)
             (reset! (r/cursor state [:current-plan]) (:plan result))
             {}))

(reg-sub :org/current-plan
         (fn [db] @(r/cursor state [:current-plan])))

(reg-sub :org/on-subscribe-nav-to-url
         (fn [db] (get-in db [:state :panels panel :on-subscribe-nav-to-url])))

(reg-event-db :org/set-on-subscribe-nav-to-url!
              [trim-v]
              (fn [db [url]]
                (assoc-in db [:state :panels panel :on-subscribe-nav-to-url] url)))

(def-action :org-subscribe-plan
  :uri (fn []
         (str "/api/org/" @(subscribe [:current-org]) "/stripe/subscribe-plan"))
  :content (fn [plan-name]
             {:plan-name plan-name})
  :process
  (fn [{:keys [db]} _ result]
    (cond (:created result)
          (do
            (reset! (r/cursor state [:changing-plan?]) false)
            (reset! (r/cursor state [:error-messsage]) nil)
            (nav-scroll-top @(subscribe [:org/on-subscribe-nav-to-url]))
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

(defn OrgPlans
  []
  (r/create-class
   {:reagent-render (fn [this]
                      (let [changing-plan? (r/cursor state [:changing-plan?])
                            updating-card? (r/cursor state [:updating-card?])
                            need-card? (r/cursor stripe/state [:need-card?])
                            error-message (r/cursor state [:error-message])
                            ;; need this for orgs
                            current-plan (subscribe [:org/current-plan])
                            test-cursor (r/cursor state [:test-cursor])
                            current-org (subscribe [:current-org])]
                        (when current-plan)
                        [:div
                         (if (nil? @current-org)
                           [Message {:negative true}
                            [MessageHeader "Organization Plans Error"]
                            "No Organization has been set. "]
                           [:div
                            (when (= (:name @current-plan) "Basic")
                              [UpgradePlan {:billing-settings-uri (str "/org/" @(subscribe [:current-org]) "/billing")
                                            :default-source-atom (subscribe [:stripe/default-source "org" @(subscribe [:current-org])])
                                            :get-default-source (partial stripe/get-org-default-source @(subscribe [:current-org]))
                                            :on-upgrade (fn [] (dispatch [:action [:org-subscribe-plan "Unlimited"]]))
                                            :on-add-payment-method #(do (dispatch [:payment/set-calling-route! "/org/plans"])
                                                                        (dispatch [:navigate [:org-payment]]))}])
                            (when (= (:name @current-plan) "Unlimited")
                              [DowngradePlan {:billing-settings-uri (str "/org/" @(subscribe [:current-org]) "/billing")
                                              :on-downgrade (fn [] (dispatch [:action [:org-subscribe-plan "Basic"]]))}])])]))
    :component-did-mount (fn [this]
                           (dispatch [:read-orgs!]))}))

(defmethod panel-content panel []
  (fn [child]
    [OrgPlans]))

(defmethod logged-out-content [:org-plans] []
  (logged-out-content :logged-out))
