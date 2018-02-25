(ns sysrev.stripe
  (:require [cljsjs.react-stripe-elements]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch reg-event-fx reg-event-db trim-v]]
            [sysrev.action.core :refer [def-action]]))

;; based on: https://github.com/stripe/react-stripe-elements
;;           https://jsfiddle.net/g9rm5qkt/

(def default-state {:error-message nil})

(def state (r/atom default-state))

(def stripe-public-key
  (-> (.getElementById js/document "stripe-public-key")
      (.getAttribute "data-stripe-public-key")))

;; Stripe elements
(def Elements (r/adapt-react-class js/ReactStripeElements.Elements))
(def CardCVCElement (r/adapt-react-class js/ReactStripeElements.CardCVCElement))
(def CardElement (r/adapt-react-class js/ReactStripeElements.CardElement))
(def CardExpiryElement (r/adapt-react-class js/ReactStripeElements.CardExpiryElement))
(def CardNumberElement (r/adapt-react-class js/ReactStripeElements.CardNumberElement))
(def PostalCodeElement (r/adapt-react-class js/ReactStripeElements.PostalCodeElement))
(def StripeProvider (r/adapt-react-class js/ReactStripeElements.StripeProvider))

(def element-style {:base {:color "#424770"
                           :letterSpacing "0.025em"
                           :fontFamily "Source Code Pro, Menlo, monospace"
                           "::placeholder" {:color "#aab7c4"}}
                    :invalid {:color "#9e2146"}}) 

(reg-event-fx
 :stripe/reset-error-message!
 [trim-v]
 (fn [_ _]
   (reset! (r/cursor state [:error-message]) nil)
   {}))

(def-action :payments/stripe-token
  :uri (fn [] "/api/payment-method")
  :content (fn [token]
             {:token token})
  :process (fn [{:keys [db]} _ {:keys [success] :as result}]
             (let [;; calling-route should be set by the :payment/set-calling-route! event
                   ;; this is just in case it wasn't set
                   ;; e.g. user went directly to /payment route
                   calling-route (or (get-in db [:state :stripe :calling-route])
                                     [:payment])]
               ;; don't need to ask for a card anymore
               (dispatch [:plans/set-need-card? false])
               ;; clear any error message that was present in plans
               (dispatch [:plans/clear-error-message!])
               ;; go back to where this panel was called from
               (dispatch [:navigate calling-route])
               ;; empty map, just interested in causing side effects
               {}))
  :on-error (fn [{:keys [db response]} _ _]
              ;; we have an error message, set it so that it can be display to the user
              (reset! (r/cursor state [:error-message]) (get-in response [:response :error :message]))
              {}))

(def StripeForm
  (r/adapt-react-class
   (js/ReactStripeElements.injectStripe
    (r/reactify-component
     (r/create-class {:display-name "stripe-reagent-form"
                      :render
                      (fn [this]
                        (let [error-message (r/cursor state [:error-message])
                              element-on-change (fn [e]
                                                  (let [e (js->clj e :keywordize-keys true)
                                                        error (:error e)]
                                                    ;; keeping the error for each element in the state atom
                                                    (swap! (r/state-atom this)
                                                           assoc (keyword (:elementType e))
                                                           error)))
                              errors? (fn []
                                        ;; we're only putting errors in the state-atom,
                                        ;; so this should be true only when there are errors
                                        (not (every? nil? (vals @(r/state-atom this)))))]
                          [:form {:on-submit (fn [e]
                                               (.preventDefault e)
                                               ;; make sure there aren't any errors
                                               (when-not (errors?)
                                                 (.then (this.props.stripe.createToken)
                                                        (fn [payload]
                                                          (when-not (:error (js->clj payload :keywordize-keys true))
                                                            (dispatch [:action [:payments/stripe-token payload]]))))))
                                  :class "StripeForm"}
                           ;; In the case where the form elements themselves catch errors, they are
                           ;; displayed below the input. Errors returned from the server are shown below the submit button
                           [:label "Card Number"
                            [CardNumberElement {:style element-style
                                                :on-change element-on-change}]]
                           [:div {:class "ui red header"}
                            @(r/cursor (r/state-atom this) [:cardNumber :message])]
                           [:label "Expiration date"
                            [CardExpiryElement {:style element-style
                                                :on-change element-on-change}]]
                           [:div {:class "ui red header"}
                            @(r/cursor (r/state-atom this) [:cardExpiry :message])]
                           [:label "CVC"
                            [CardCVCElement {:style element-style
                                             :on-change element-on-change}]]
                           ;; this might not ever even generate an error, included here
                           ;; for consistency
                           [:div {:class "ui red header"}
                            @(r/cursor (r/state-atom this) [:cardCvc :message])]
                           ;; unexplained behavior: Why do you need a minimum of
                           ;; 6 digits to be entered in the cardNumber input for
                           ;; in order for this to start checking input?
                           [:label "Postal code"
                            [PostalCodeElement {:style element-style
                                                :on-change element-on-change}]]
                           [:div {:class "ui red header"}
                            @(r/cursor (r/state-atom this) [:postalCode :message])]
                           [:button {:class (str "ui primary button "
                                                 (when (errors?)
                                                   "disabled"))} "Use Card"]
                           ;; shows the errors returned from the server (our own, or stripe.com)
                           (when @error-message
                             [:div {:class "ui red header"}
                              @error-message])]))})))))

(defn StripeCardInfo
  []
  [StripeProvider {:apiKey stripe-public-key}
   [Elements
    [StripeForm]]])
