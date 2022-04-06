(ns sysrev.stripe
  (:require ["@stripe/stripe-js" :as stripe-js :refer [loadStripe]]
            ["@stripe/react-stripe-js" :as RStripe :refer [useStripe]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.views.semantic :refer [Button Form]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:stripe]
                   :state state :get [panel-get ::get] :set [panel-set ::set])


;;; based on: https://github.com/stripe/react-stripe-elements
;;;           https://jsfiddle.net/g9rm5qkt/

(def ^:private stripe-public-key
  (some-> (.getElementById js/document "stripe-public-key")
          (.getAttribute "data-stripe-public-key")))

#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private stripe-client-id
  (some-> (.getElementById js/document "stripe-client-id")
          (.getAttribute "data-stripe-client-id")))

(def ^:private stripe-promise (loadStripe stripe-public-key))

;; Stripe elements
(def ^:private Elements (r/adapt-react-class RStripe/Elements))
(def ^:private CardCvcElement (r/adapt-react-class RStripe/CardCvcElement))
(def ^:private CardExpiryElement (r/adapt-react-class RStripe/CardExpiryElement))
(def ^:private CardNumberElement (r/adapt-react-class RStripe/CardNumberElement))

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
              :dispatch-n [[:data/load [:user/default-source user-id]]]})
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

;; https://stripe.com/docs/payments/cards/saving-cards-without-payment
;; https://stripe.com/docs/testing#regulatory-cards
;; https://stripe.com/docs/stripe-js/reference#stripe-handle-card-setup

;; when migrating existing customers, will need to use this
;; https://stripe.com/docs/payments/payment-methods#compatibility
;; https://stripe.com/docs/strong-customer-authentication/faqs
;; https://stripe.com/docs/payments/cards/charging-saved-cards#notify
;; https://stripe.com/docs/billing/subscriptions/payment#handling-action-required - for subscriptions

(defn- StripeForm [{:keys [add-payment-fn submit-button-text]}]
  (let [card-element (r/atom nil)]
    (fn []
      (let [this (r/current-component)
            state (r/state this)
            ^js stripe (useStripe this)
            element-style {:base {:color "#424770"
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
                      (not (every? nil? (vals state))))
            client-secret (subscribe [:stripe/client-secret])
            disabled? @(subscribe [::form-disabled])]
        [Form
         {:class "StripeForm"
          :on-submit
          (util/wrap-prevent-default
           (fn []
             (if (errors?)
               (dispatch [::form-disabled false])
               (do (dispatch [::form-disabled true])
                   (-> (.handleCardSetup stripe @client-secret @card-element)
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
          [CardNumberElement {:on-change element-on-change
                              :on-ready #(reset! card-element %)
                              :options {:style element-style
                                        :disabled disabled?}}]]
         [:div.ui.red.header (-> state :cardNumber :message)]
         [:label "Expiration Date"
          [CardExpiryElement {:on-change element-on-change
                              :options {:style element-style
                                        :disabled disabled?}}]]
         [:div.ui.red.header (-> state :cardExpiry :message)]
         [:label "CVC"
          [CardCvcElement {:on-change element-on-change
                           :options {:style element-style
                                     :disabled disabled?}}]]
         ;; this might not ever even generate an error, included here
         ;; for consistency
         [:div.ui.red.header (-> state :cardCvc :message)]
         [Button {:type :submit, :primary true, :class "use-card"
                  :disabled (or disabled? (errors?))}
          submit-button-text]
         ;; shows the errors returned from the server (our own, or stripe.com)
         (when-let [msg @(subscribe [::error-message])]
           [:div.ui.red.header msg])]))))

(defn StripeCardInfo
  "add-payment-fn is a fn of payload returned by Stripe"
  [{:keys [add-payment-fn submit-button-text]}]
  (fn [{:keys [add-payment-fn submit-button-text]}]
    (with-loader [[:stripe/client-secret]] {}
      [Elements {:stripe stripe-promise}
       ;; reference:
       ;; https://github.com/reagent-project/reagent/blob/master/doc/ReactFeatures.md
       ;; * wrapping with [:f> ...] creates a React functional component
       ;; * this is needed for the `useStripe` hook
       [:f> (StripeForm {:add-payment-fn add-payment-fn
                         :submit-button-text (or submit-button-text
                                                 "Save Payment Information")})]])))

;; https://stripe.com/docs/connect/quickstart
;; use: phone number: 000-000-0000
;;      text confirm number: 000 000
;;      use debit card:  4000 0566 5566 5556
