(ns sysrev.stripe
  (:require ["react-stripe-elements" :as RStripe]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-sub
                                   reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.views.semantic :refer [Button Form]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state with-loader]]))

;; for clj-kondo
(declare panel state)

(def user-pro-plans #{"Unlimited_User"
                      "Unlimited_User_Annual"})
(def org-pro-plans  #{"Unlimited_Org"
                      "Unlimited_Org_Annual"})
(def pro-plans      (set (concat user-pro-plans org-pro-plans)))
(def basic-plans    #{"Basic"})

(defn pro? [plan-nickname]
  (contains? pro-plans plan-nickname))
(defn user-pro? [plan-nickname]
  (contains? user-pro-plans plan-nickname))
(defn org-pro? [plan-nickname]
  (contains? org-pro-plans plan-nickname))
(defn basic? [plan-nickname]
  (contains? basic-plans plan-nickname))

(setup-panel-state panel [:stripe] {:state-var state
                                    :get-fn panel-get :set-fn panel-set
                                    :get-sub ::get :set-event ::set})

;;; based on: https://github.com/stripe/react-stripe-elements
;;;           https://jsfiddle.net/g9rm5qkt/

(def ^:private stripe-public-key
  (some-> (.getElementById js/document "stripe-public-key")
          (.getAttribute "data-stripe-public-key")))

#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private stripe-client-id
  (some-> (.getElementById js/document "stripe-client-id")
          (.getAttribute "data-stripe-client-id")))

;; Stripe elements
(def Elements (r/adapt-react-class RStripe/Elements))
(def CardCVCElement (r/adapt-react-class RStripe/CardCVCElement))
(def CardExpiryElement (r/adapt-react-class RStripe/CardExpiryElement))
(def CardNumberElement (r/adapt-react-class RStripe/CardNumberElement))
(def StripeProvider (r/adapt-react-class RStripe/StripeProvider))

(reg-sub      ::form-disabled #(boolean (panel-get % :form-disabled)))
(reg-event-db ::form-disabled [trim-v]
              (fn [db [disabled]] (panel-set db :form-disabled disabled)))

(reg-sub      ::error-message #(panel-get % :error-message))
(reg-event-db ::error-message [trim-v]
              (fn [db [msg]] (panel-set db :error-message msg)))

(def-action :stripe/add-payment-user
  :uri (fn [user-id _] (str "/api/user/" user-id "/stripe/payment-method"))
  :content (fn [_ payment_method] {:payment_method payment_method})
  :process (fn [{:keys [db]} [user-id _] _]
             {:db (-> (panel-set db :need-card? false)
                      (panel-set    :error-message nil)
                      (panel-set    :form-disabled false))
              :dispatch-n [[:plans/clear-error-message!]
                           [:data/load [:user/default-source user-id]]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db :error-message (:message error))}))

(def-action :stripe/add-payment-org
  :uri (fn [org-id _] (str "/api/org/" org-id "/stripe/payment-method"))
  :content (fn [_ payment_method] {:payment_method payment_method})
  :process (fn [{:keys [db]} [org-id _] _]
             {:db (-> (panel-set db :need-card? false)
                      (panel-set    :error-message nil)
                      (panel-set    :form-disabled false))
              :dispatch [:data/load [:org/default-source org-id]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db :error-message (:message error))}))

(def-data :user/default-source
  :uri (fn [user-id] (str "/api/user/" user-id "/stripe/default-source"))
  :loaded? (fn [db user-id]
             (-> (get-in db [:data :default-stripe-source :user])
                 (contains? user-id)))
  :process (fn [{:keys [db]} [user-id] {:keys [default-source]}]
             {:db (assoc-in db [:data :default-stripe-source :user user-id] default-source)}))
(reg-sub  :user/default-source
          (fn [db [_ user-id]]
            (let [user-id (or user-id (current-user-id db))]
              (get-in db [:data :default-stripe-source :user user-id]))))

(def-data :org/default-source
  :uri (fn [org-id] (str "/api/org/" org-id "/stripe/default-source"))
  :loaded? (fn [db org-id]
             (-> (get-in db [:data :default-stripe-source :org])
                 (contains? org-id)))
  :process (fn [{:keys [db]} [org-id] {:keys [default-source]}]
             {:db (assoc-in db [:data :default-stripe-source :org org-id] default-source)}))
(reg-sub  :org/default-source
          (fn [db [_ org-id]]
            (get-in db [:data :default-stripe-source :org org-id])))

(def-data :stripe/client-secret
  :method :post
  :loaded? (fn [db] (-> (get-in db [:data :stripe])
                        (contains? :client-secret)))
  :uri (constantly "/api/stripe/setup-intent")
  :process (fn [{:keys [db]} [] {:keys [client_secret]}]
             {:db (assoc-in db [:data :stripe :client-secret] client_secret)}))
(reg-sub  :stripe/client-secret #(get-in % [:data :stripe :client-secret]))
(reg-event-db ::clear-secret #(util/dissoc-in % [:data :stripe :client-secret]))

(defn- inject-stripe [comp]
  (-> comp r/reactify-component RStripe/injectStripe r/adapt-react-class))

;; https://stripe.com/docs/payments/cards/saving-cards-without-payment
;; https://stripe.com/docs/testing#regulatory-cards
;; https://stripe.com/docs/stripe-js/reference#stripe-handle-card-setup

;; when migrating existing customers, will need to use this
;; https://stripe.com/docs/payments/payment-methods#compatibility
;; https://stripe.com/docs/strong-customer-authentication/faqs
;; https://stripe.com/docs/payments/cards/charging-saved-cards#notify
;; https://stripe.com/docs/billing/subscriptions/payment#handling-action-required - for subscriptions

(defn- StripeForm [{:keys [add-payment-fn submit-button-text]}]
  (inject-stripe
   (r/create-class
    {:display-name "stripe-reagent-form"
     :render
     (fn [^js this]
       (let [element-style {:base {:color "#424770"
                                   :letterSpacing "0.025em"
                                   :fontFamily "Source Code Pro, Menlo, monospace"
                                   "::placeholder" {:color "#aab7c4"}}
                            :invalid {:color "#9e2146"}}
             element-on-change #(let [{:keys [elementType error]}
                                      (js->clj % :keywordize-keys true)]
                                  ;; keeping the error for each element in the state atom
                                  (swap! (r/state-atom this)
                                         assoc (keyword elementType) error))
             errors? (fn []
                       ;; we're only putting errors in the state-atom,
                       ;; so this should be true only when there are errors
                       (not (every? nil? (vals (r/state this)))))
             client-secret (subscribe [:stripe/client-secret])
             disabled? @(subscribe [::form-disabled])
             card-element (r/atom nil)]
         [Form
          {:class "StripeForm"
           :on-submit
           (util/wrap-prevent-default
            (fn []
              (if (errors?)
                (dispatch [::form-disabled false])
                (do (dispatch [::form-disabled true])
                    (-> (.props.stripe.handleCardSetup this @client-secret @card-element)
                        (.then #(let [{:keys [error setupIntent]}
                                      (js->clj % :keywordize-keys true)]
                                  (if error
                                    (do (dispatch [::error-message (:message error)])
                                        (dispatch [::form-disabled false]))
                                    (do (dispatch [::error-message nil])
                                        (add-payment-fn (:payment_method setupIntent)))))))))))}
          ;; In the case where the form elements themselves catch errors,
          ;; they are displayed below the input. Errors returned from the
          ;; server are shown below the submit button.
          [:label "Card Number"
           [CardNumberElement {:style element-style
                               :on-change element-on-change
                               :disabled disabled?
                               :onReady #(reset! card-element %)}]]
          [:div.ui.red.header (-> (r/state this) :cardNumber :message)]
          [:label "Expiration Date"
           [CardExpiryElement {:style element-style
                               :on-change element-on-change
                               :disabled disabled?}]]
          [:div.ui.red.header (-> (r/state this) :cardExpiry :message)]
          [:label "CVC"
           [CardCVCElement {:style element-style
                            :on-change element-on-change
                            :disabled disabled?}]]
          ;; this might not ever even generate an error, included here
          ;; for consistency
          [:div.ui.red.header (-> (r/state this) :cardCvc :message)]
          [Button {:disabled (or disabled? (errors?))
                   :class "use-card"
                   :primary true}
           submit-button-text]
          ;; shows the errors returned from the server (our own, or stripe.com)
          (when-let [msg @(subscribe [::error-message])]
            [:div.ui.red.header msg])]))})))

(defn StripeCardInfo
  "add-payment-fn is a fn of payload returned by Stripe"
  [{:keys [add-payment-fn submit-button-text]}]
  (dispatch-sync [::set [] {}])
  (dispatch-sync [::clear-secret])
  (fn [{:keys [add-payment-fn submit-button-text]}]
    (with-loader [[:stripe/client-secret]] {}
      (let [submit-button-text (or submit-button-text "Save Payment Information")]
        [StripeProvider {:apiKey stripe-public-key}
         [Elements [(StripeForm {:add-payment-fn add-payment-fn
                                 :submit-button-text submit-button-text})]]]))))

;; https://stripe.com/docs/connect/quickstart
;; use: phone number: 000-000-0000
;;      text confirm number: 000 000
;;      use debit card:  4000 0566 5566 5556
