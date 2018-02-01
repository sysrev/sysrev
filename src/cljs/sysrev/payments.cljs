(ns sysrev.payments
  (:require [cljsjs.react-stripe-elements]
            [reagent.core :as r]))

;; for advanced compilation, you may need to use
;; (r/adapt-react-class (aget js/ReactStripeElements "StripeProvider")
(def StripeProvider (r/adapt-react-class js/ReactStripeElements.StripeProvider))

(def Elements (r/adapt-react-class js/ReactStripeElements.Elements))

(def CardElement (r/adapt-react-class js/ReactStripeElements.CardElement))
(def CardNumberElement (r/adapt-react-class js/ReactStripeElements.CardNumberElement))
(def CardExpiryElement (r/adapt-react-class js/ReactStripeElements.CardExpiryElement))
(def CardCVCElement (r/adapt-react-class js/ReactStripeElements.CardCVCElement))
(def PostalCodeElement (r/adapt-react-class js/ReactStripeElements.PostalCodeElement))

(def element-style {:base {:color "#424770"
                           :letterSpacing "0.025em"
                           :fontFamily "Source Code Pro, Menlo, monospace"
                           "::placeholder" { :color "#aab7c4"}}
                    :invalid {:color "#9e2146"}}) 

(defn StripeCardInfo
  []
  [:div.ui.segment
   [StripeProvider {:apiKey "***REMOVED***"}
    [Elements [:form {:on-submit (fn [e]
                                   (.preventDefault e)
                                   (.log js/console "Form submitted"))}
               [:label "Card Number"
                [CardNumberElement {:onBlur #(.log js/console "I blurred")
                                    :onChange #(.log js/console "I changed")
                                    :onFocus #(.log js/console "I focused")
                                    :onReady #(.log js/console "I'm ready")
                                    :style element-style} ]]
               [:label "Expiration date"
                [CardExpiryElement {:onBlur #(.log js/console "I blurred")
                                    :onChange #(.log js/console "I changed")
                                    :onFocus #(.log js/console "I focused")
                                    :onReady #(.log js/console "I'm ready")
                                    :style element-style}]]
               [:label "CVC"
                [CardCVCElement {:onBlur #(.log js/console "I blurred")
                                 :onChange #(.log js/console "I changed")
                                 :onFocus #(.log js/console "I focused")
                                 :onReady #(.log js/console "I'm ready")
                                 :style element-style}]]
               [:label "Postal code"
                [PostalCodeElement {:onBlur #(.log js/console "I blurred")
                                    :onChange #(.log js/console "I changed")
                                    :onFocus #(.log js/console "I focused")
                                    :onReady #(.log js/console "I'm ready")
                                    :style element-style}]]
               [:button.ui.primary.button {:on-click #(.log js/console "I submitted")}
                "Pay"]]]]])
