(ns sysrev.views.panels.orgs
  (:require [ajax.core :refer [POST GET]]
            [goog.uri.utils :as uri-utils]
            [medley.core :as medley]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.base :refer [active-route]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe :refer [StripeCardInfo]]
            [sysrev.util :as util]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.org.plans :refer [Unlimited ToggleInterval TeamProPlanPrice price-summary]]
            [sysrev.views.semantic :refer
             [Form FormField Button Segment Header Input Message MessageHeader Grid Row Column ListUI ListItem Loader]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:orgs] {:state-var state})

(defn create-org! [org-name & {:keys [redirect-subpath]
                               :or {redirect-subpath "/projects"}}]
  (let [create-org-retrieving? (r/cursor state [:create-org-retrieving?])
        create-org-error (r/cursor state [:create-org-error])]
    (when-not @create-org-retrieving?
      (reset! create-org-retrieving? true)
      (POST "/api/org"
            {:params {:org-name org-name}
             :headers {"x-csrf-token" @(subscribe [:csrf-token])}
             :handler (fn [{:keys [result]}]
                        (let [new-org-id (:id result)]
                          (reset! create-org-retrieving? false)
                          (nav-scroll-top (str "/org/" new-org-id redirect-subpath))))
             :error-handler (fn [resp]
                              (reset! create-org-retrieving? false)
                              (reset! create-org-error (-> resp :response :error :message)))}))))

(defn validate-org [org-name & {:keys [on-success]}]
  (let [create-org-error (r/cursor state [:create-org-error])
        create-org-retrieving? (r/cursor state [:create-org-retrieving?])]
    (when-not @create-org-retrieving?
      (reset! create-org-retrieving? true)
      (GET "/api/org/valid-name"
           {:params {:org-name
                     ;; this is a check that if it isn't a seq, make it blank string, because nil is sent as the string "null" to the server
                     (if (seq org-name)
                       org-name
                       "")}
            :headers {"x-csrf-token" @(subscribe [:csrf-token])}
            :handler (fn [{:keys [valid]}]
                       (reset! create-org-error nil)
                       (on-success)
                       (reset! create-org-retrieving? false))
            :error-handler (fn [resp]
                             (reset! create-org-error (-> resp :response :error :message))
                             (reset! create-org-retrieving? false))}))))

(defn create-org-pro! [new-org-name new-plan payment-method]
  (let [create-org-error (r/cursor state [:create-org-error])
        plan-error-message (r/cursor state [:plan-error-message])
        stripe-error-message (r/cursor stripe/state [:error-message])
        create-org-retrieving? (r/cursor state [:create-org-retrieving?])]
    (when-not @create-org-retrieving?
      (reset! create-org-retrieving? true)
      (reset! create-org-error nil)
      (reset! stripe-error-message nil)
      (POST "/api/org/pro"
            {:params {:org-name new-org-name
                      :plan new-plan
                      :payment-method payment-method}
             :headers {"x-csrf-token" @(subscribe [:csrf-token])}
             :handler (fn [{:keys [result]}]
                        (let [new-org-id (:id result)]
                          ;;(reset! create-org-retrieving? false)
                          (nav-scroll-top (str "/org/" new-org-id "/billing"))))
             :error-handler (fn [resp]
                              (let [{:keys [stripe-error org-error plan-error]} (-> resp :response)]
                                (reset! create-org-error (:message org-error))
                                (reset! stripe-error-message (:message stripe-error))
                                (reset! plan-error-message (:message plan-error)))
                              (reset! create-org-retrieving? false))}))))

(defn CreateOrgForm []
  (let [new-org (r/cursor state [:new-org])
        create-org-error (r/cursor state [:create-org-error])
        create-org-retrieving? (r/cursor state [:create-org-retrieving?])
        panel-type (uri-utils/getParamValue @active-route "type")
        plan (uri-utils/getParamValue @active-route "plan")]
    (r/create-class
     {:reagent-render
      (fn []
        [:div
         [Form {:on-submit #(cond (= plan "pro")
                                  (validate-org @new-org
                                                :on-success
                                                (fn [_]
                                                  (reset! create-org-retrieving? true)
                                                  (nav-scroll-top "/create/org" :params
                                                                  {:new-org-name @new-org})))
                                  (or (= panel-type "new-account")
                                      (= panel-type "existing-account"))
                                  (create-org! @new-org :redirect-subpath "/plans" )
                                  :else (create-org! @new-org))
                :loading @create-org-retrieving?}
          [FormField
           [Input {:placeholder "Organization Name"
                   :id "create-org-input"
                   :default-value @new-org
                   :action (r/as-element [Button {:primary true
                                                  :class "create-organization"
                                                  :id "create-org-button"} "Create"])
                   :on-change (util/on-event-value
                               #(do (reset! create-org-error nil)
                                    (reset! new-org %)))}]]]
         (when (seq @create-org-error)
           [Message {:negative true
                     :onDismiss #(reset! create-org-error nil)}
            [MessageHeader {:as "h4"} "Create Org Error"]
            @create-org-error])])
      :get-initial-state
      (fn [_this]
        (reset! new-org "")
        {})
      :component-did-mount
      (fn [_this]
        (reset! create-org-error nil))})))

(defn CreateOrg []
  [Segment {:secondary true}
   [Header {:as "h4" :dividing true} "Create a New Organization"]
   [CreateOrgForm]])

(defn CreateOrgPro []
  (let [plan-error-message (r/cursor state [:plan-error-message])
        new-plan (r/cursor state [:new-plan])]
    (fn [new-org-name available-plans]
      (when-not (nil? @available-plans)
        (reset! new-plan (medley/find-first #(= (:nickname %) "Unlimited_Org") @available-plans)))
      (if (empty? @available-plans)
        [Loader {:active true
                 :inline "centered"}]
        [:div
         [Grid {:stackable true :columns 2 :class "upgrade-plan"}
          [Column
           [Grid [Row [Column
                       [Unlimited new-plan]]]]]
          [Column
           [Grid
            [Row
             [Column
              [ToggleInterval {:style {:margin-top "0"}
                               :new-plan new-plan
                               :available-plans @available-plans}]
              [:p {:style {:color "green"}} "Pay yearly and get the first month free!"]
              [ListUI {:divided true}
               [:h4 "New Monthly Bill"]
               [ListItem [TeamProPlanPrice new-plan]]
               [:h4 "Billing Information"]
               [StripeCardInfo {:add-payment-fn
                                (fn [payload]
                                  (create-org-pro! new-org-name @new-plan payload))
                                :submit-button-text "Subscribe To Plan"}]
               [:p {:style {:margin-top "1em"}}
                "By clicking 'Subscribe To Plan' you authorize Insilica LLC to send instructions to the financial institution that issued your card to take payments from your card account in accordance with the above terms."]             
               (when @plan-error-message
                 [Message {:negative true}
                  [MessageHeader "Change Plan Error"]
                  [:p @plan-error-message]])]]]]]]]))))

(defn SubscribeOrgPanel
  []
  (r/create-class
   {:reagent-render
    (fn [_]
      (let [panel-type  (uri-utils/getParamValue @active-route "panel-type")
            plan (uri-utils/getParamValue @active-route "plan")
            new-org-name (uri-utils/getParamValue @active-route "new-org-name")
            create-org-error (r/cursor state [:create-org-error])
            available-plans (subscribe [:org/available-plans])]
        (if new-org-name
          [:div
           [:h3 {:style {:text-align "center"}} "Next, enter payment information and choose a payment interval for your team"]
           [:div {:style {:margin-bottom "1em"}} [:h2 (str "New Team: ") new-org-name]
            (when @create-org-error
              [Message {:negative true
                        :onDismiss #(reset! create-org-error nil)}
               [MessageHeader {:as "h4"} "Create Org Error"]
               @create-org-error])]
           [CreateOrgPro new-org-name available-plans]]
          [:div
           (cond (= plan "basic") nil
                 (= panel-type "new-account")
                 [:h3 {:style {:text-align "center"}} "Next, create a Sysrev organization for your team"]                   
                 (= panel-type "existing-account")
                 [:h3 {:style {:text-align "center"}} "First, create a Sysrev organization for your team"])
           [Segment {:secondary true
                     :class "ui segment auto-margin auth-segment" }
            [Header {:as "h4" :dividing true} "Create a New Organization"]
            [CreateOrgForm]]])))
    :component-did-mount (fn [_this]
                           (dispatch [:data/load [:org/available-plans]]))}))

(defmethod panel-content panel []
  (fn [_child] [SubscribeOrgPanel]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute
 create-org "/create/org" []
 (dispatch [:set-active-panel [:orgs]]))

