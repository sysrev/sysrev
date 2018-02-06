(ns sysrev.payments
  (:require [cljsjs.react-stripe-elements]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch]]
            [sysrev.action.core :refer [def-action]]))

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

(def-action :payments/stripe-token
  :uri (fn [] "/api/stripe-token")
  :content (fn [token]
             {:token token})
  :process (fn [_ _ {:keys [success] :as result}]
             (if success
               (.log js/console "token uplaoded"))))

(def StripeForm
  (r/adapt-react-class
   (js/ReactStripeElements.injectStripe
    (r/reactify-component
     (r/create-class {:display-name "foo-form"
                      :render
                      (fn [this]
                        [:form {:on-submit (fn [e]
                                             (.preventDefault e)
                                             (.then (this.props.stripe.createToken)
                                                    (fn [payload]
                                                      (dispatch [:action [:payments/stripe-token payload]]))))
                                :class "StripeForm"}
                         [:label "Card Number"
                          [CardNumberElement {:style element-style}]]
                         [:label "Expiration date"
                          [CardExpiryElement {:style element-style}]]
                         [:label "CVC"
                          [CardCVCElement {:style element-style}]]
                         [:label "Postal code"
                          [PostalCodeElement {:style element-style}]]
                         [:button.ui.primary.button "Pay"]])})))))

(defn StripeCardInfo
  []
  [:div.ui.secondary.segment
   [StripeProvider {:apiKey stripe-public-key}
    [Elements
     [StripeForm]]]])
