(ns sysrev.views.panels.create-org
  (:require [medley.core :as medley :refer [find-first]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav :refer [nav]]
            [sysrev.stripe :as stripe :refer [StripeCardInfo]]
            [sysrev.util :as util]
            [sysrev.views.panels.org.plans :refer [Unlimited ToggleInterval TeamProPlanPrice]]
            [sysrev.views.semantic :refer [Form FormField Button Segment Header Input Message
                                           MessageHeader Grid Row Column ListUI ListItem Loader]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:orgs]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(defn- org-ajax-active? []
  (or (loading/any-action-running? :only :org/create)
      (loading/any-action-running? :only :org/create-pro)
      (loading/any-loading? :only :org/valid-name)))

(def-action :org/create
  :uri (constantly "/api/org")
  :content (fn [org-name _] {:org-name org-name})
  :process (fn [_ [_ redirect-subpath] {:keys [id]}]
             {:nav [(str "/org/" id (or redirect-subpath "/projects"))]})
  :on-error (fn [{:keys [error]} _ _]
              {:dispatch [::set :create-org-error (:message error)]}))

(def-action :org/validate-name
  :method :get
  :uri (constantly "/api/org/valid-name")
  :content (fn [org-name _] {:org-name (or (not-empty org-name) "")})
  :process (fn [_ [_ on-success] _]
             (when on-success (on-success))
             {:dispatch [::set :create-org-error nil]})
  :on-error (fn [{:keys [error]} _ _]
              {:dispatch [::set :create-org-error (:message error)]}))

(def-action :org/create-pro
  :uri (constantly "/api/org/pro")
  :content (fn [org-name plan payment-method]
             {:org-name org-name :plan plan :payment-method payment-method})
  :process (fn [_ _ {:keys [id]}]
             {:nav [(str "/org/" id "/billing")]
              :dispatch [::set :create-org-error nil]})
  :on-error (fn [{:keys [error]} _ _]
              (let [{:keys [stripe-error org-error plan-error]} error]
                {:dispatch-n [[::stripe/set :error-message (:message stripe-error)]
                              [::set :create-org-error (:message org-error)]
                              [::set :plan-error-message (:message plan-error)]]})))

(defn- CreateOrgForm []
  (let [{:keys [panel-type plan]} @(subscribe [::get :params])
        new-org (r/cursor state [:new-org])
        create-org-error (r/cursor state [:create-org-error])]
    [:div
     [Form {:on-submit (fn []
                         (cond (= plan "pro")
                               (run-action :org/validate-name @new-org
                                           #(nav "/create/org"
                                                 :params {:new-org-name @new-org}))
                               (or (= panel-type "new-account")
                                   (= panel-type "existing-account"))
                               (run-action :org/create @new-org "/plans")
                               :else
                               (run-action :org/create @new-org)))
            :loading (org-ajax-active?)}
      [FormField
       [Input {:placeholder "Organization Name"
               :id "create-org-input"
               :default-value (or @new-org "")
               :action (r/as-element [Button {:primary true
                                              :class "create-organization"
                                              :id "create-org-button"} "Create"])
               :on-change (util/on-event-value
                           #(do (reset! create-org-error nil)
                                (reset! new-org %)))}]]]
     (when (seq @create-org-error)
       [Message {:negative true :on-dismiss #(reset! create-org-error nil)}
        [MessageHeader {:as "h4"} "Create Org Error"]
        @create-org-error])]))

(defn CreateOrg []
  [Segment {:secondary true}
   [Header {:as "h4" :dividing true} "Create a New Organization"]
   [CreateOrgForm]])

(defn- CreateOrgPro [new-org-name]
  (let [available-plans @(subscribe [:org/available-plans])
        {:keys [plan-error-message]} @(subscribe [::get])
        new-plan (or @(subscribe [::get :new-plan])
                     (find-first #(= (:nickname %) "Unlimited_Org")
                                 available-plans))]
    (if (empty? available-plans)
      [Loader {:active true :inline "centered"}]
      [:div
       [Grid {:stackable true :columns 2 :class "upgrade-plan"}
        [Column
         [Grid [Row [Column [Unlimited new-plan]]]]]
        [Column
         [Grid
          [Row
           [Column
            [ToggleInterval {:style {:margin-top "0"}
                             :available-plans available-plans
                             :new-plan new-plan
                             :set-plan #(dispatch [::set :new-plan %])}]
            [:p {:style {:color "green"}}
             "Pay yearly and get the first month free!"]
            [ListUI {:divided true}
             [:h4 "New Monthly Bill"]
             [ListItem [TeamProPlanPrice new-plan]]
             [:h4 "Billing Information"]
             [StripeCardInfo
              {:add-payment-fn (fn [payload]
                                 (run-action :org/create-pro new-org-name new-plan payload))
               :submit-button-text "Subscribe To Plan"}]
             [:p {:style {:margin-top "1em"}}
              "By clicking 'Subscribe To Plan' you authorize Insilica LLC to send instructions to the financial institution that issued your card to take payments from your card account in accordance with the above terms."]
             (when plan-error-message
               [Message {:negative true}
                [MessageHeader "Change Plan Error"]
                [:p plan-error-message]])]]]]]]])))

(defn- SubscribeOrgPanel []
  (let [{:keys [panel-type plan new-org-name]} @(subscribe [::get :params])
        create-org-error (r/cursor state [:create-org-error])]
    (if new-org-name
      [:div
       [Header {:as "h3" :align "center"}
        "Next, enter payment information and choose a payment interval for your team"]
       [:div {:style {:margin-bottom "1em"}}
        [Header {:as "h2"} (str "New Team: ") new-org-name]
        (when @create-org-error
          [Message {:negative true :onDismiss #(reset! create-org-error nil)}
           [MessageHeader {:as "h4"} "Create Org Error"]
           @create-org-error])]
       [CreateOrgPro new-org-name]]
      [:div
       (when-not (= plan "basic")
         [Header {:as "h3" :align "center"}
          (case panel-type
            "new-account"       "Next, create a Sysrev organization for your team"
            "existing-account"  "First, create a Sysrev organization for your team"
            nil)])
       [Segment {:secondary true :class "auto-margin auth-segment" }
        [Header {:as "h4" :dividing true} "Create a New Organization"]
        [CreateOrgForm]]])))

(def-panel :uri "/create/org" :panel panel
  :on-route (do (dispatch [:set-active-panel panel])
                (dispatch [:data/load [:org/available-plans]])
                (dispatch [::set :params (util/get-url-params)])
                (dispatch [::set :new-org nil])
                (dispatch [::set :create-org-error nil]))
  :content (with-loader [[:org/available-plans]] {}
             [SubscribeOrgPanel])
  :require-login true)
