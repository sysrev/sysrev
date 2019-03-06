(ns sysrev.paypal
  (:require [ajax.core :refer [POST]]
            [cljsjs.accounting]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as acct]
            [sysrev.views.semantic :as s]
            [sysrev.shared.util :as sutil])
  (:require-macros [reagent.interop :refer [$]]))

(def panel [:project :project :paypal])

(def state (r/cursor app-db [:state :panel panel]))

(def minimum-amount "10.00")

(def paypal-env
  (some-> (.getElementById js/document "paypal-env")
          (.getAttribute "data-paypal-env")))

(def paypal-client-id
  (some-> (.getElementById js/document "paypal-client-id")
          (.getAttribute "data-paypal-client-id")))
;; https://developer.paypal.com/docs/checkout/how-to/customize-flow/#interactive-code-demo

(defn add-funds
  [state paypal-response]
  (let [project-id @(subscribe [:active-project-id])
        user-id @(subscribe [:self/user-id])
        error-message (r/cursor state [:error-message])
        success-message (r/cursor state [:success-message])
        loading? (r/cursor state [:loading?])
        user-defined-support-level (r/cursor state [:user-defined-support-level])]
    (reset! success-message nil)
    (reset! error-message nil)
    (POST "/api/paypal/add-funds"
          {:params {:project-id project-id
                    :user-id user-id
                    :response paypal-response}
           :headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :handler
           (fn [response]
             (reset! loading? false)
             (reset! success-message
                     (str "Your payment of "
                          @user-defined-support-level
                          " has been received and will be available after it has been processed"))
             (reset! user-defined-support-level (-> minimum-amount (sutil/ensure-prefix "$")))
             (dispatch [:project/get-funds]))
           :error-handler
           (fn [error]
             (reset! loading? false)
             (reset! error-message (get-in error [:response :error :message])))})))

(defn on-authorize-default
  "Default function for PayPalButton on-authorize. data and actions are
  default values that are required by PayPal"
  [data actions]
  (-> ($ actions payment.execute)
      ($ then #($ js/window alert "Payment Complete!"))))

(defn on-error-default
  "Default function for PayPalButton on-authorize. err is an Error
  object. The message property on the err returned from PayPal is a
  long string that is difficult to parse"
  [err]
  ($ js/window alert "There was an error with PayPal"))

;; https://developer.paypal.com/docs/checkout/quick-start/
;; this also depends on [:script {:src "https://www.paypalobjects.com/api/checkout.js"}]
;; being in index.clj

;; test information associated with:  james+sandbox+1@insilica.co
;; visa number: 4032033511927936
;; exp: 11/2023
;; everything else you can make up, except the city must match the zip code
(defn PayPalButton
  "Create a PayPalButton where amount-atom derefs to an integer amount
  of cents. Optional on-authorize is a fn of data and
  actions (fn [data actions] ...) to pass to onAuthorize"
  [amount-atom & {:keys [on-authorize on-error]
                  :or {on-authorize  on-authorize-default
                       on-error      on-error-default}}]
  (r/create-class
   {:reagent-render
    (fn [this]
      [:div {:id "paypal-button"}])
    :component-did-mount
    (fn [this]
      (-> ($ js/paypal :Button)
          ($ render
             (clj->js
              {:env paypal-env
               :style {:label "pay"
                       :layout "vertical"
                       :size "responsive"
                       :shape "rect"
                       :color "gold"}
               :disabled true
               :commit true
               :funding {:allowed [($ js/paypal :FUNDING.CARD)
                                   ($ js/paypal :FUNDING.CREDIT)]
                         :disallowed []}
               :client
               (condp = paypal-env
                 "sandbox"     {:sandbox paypal-client-id}
                 "production"  {:production paypal-client-id})
               :payment
               (fn [data actions]
                 ($ actions payment.create
                    (clj->js
                     {:payment
                      {:transactions
                       [{:amount {:total (acct/format-money @amount-atom "")
                                  :currency "USD"}}]}
                      ;; what other experience fields can we set?
                      :experience {:input_fields {:no_shipping 1}}})))
               ;; https://developer.paypal.com/docs/checkout/how-to/customize-flow/#show-a-confirmation-page
               :onAuthorize on-authorize
               :onError on-error})
             "#paypal-button")))}))

(defn amount-validation
  "Validate that the dollar amount string (e.g. '$10.00') is in the correct format"
  [amount]
  (cond
    ;; appears to not be a valid amount
    (not (re-matches acct/valid-usd-regex amount))
    "Amount is not valid"
    (< (acct/string->cents amount) (acct/string->cents minimum-amount))
    (str "Minimum payment is " (acct/format-money minimum-amount "$"))
    :else nil))

(defn AddFunds []
  (let [support-level (r/cursor state [:support-level])
        user-defined-support-level (r/cursor state [:user-defined-support-level])
        error-message (r/cursor state [:error-message])
        loading? (r/cursor state [:loading?])
        success-message (r/cursor state [:success-message])
        paypal-disabled? (r/cursor state [:paypal-disabled?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div.ui.segment
         [:h4.ui.dividing.header "Add Funds"]
         [s/Form {:on-submit
                  #(let [cents (acct/string->cents @user-defined-support-level)]
                     (cond (and (= @support-level :user-defined)
                                (= cents 0))
                           (reset! user-defined-support-level "$0.00")
                           (and (= @support-level :user-defined)
                                (> cents 0))
                           (reset! loading? true)
                           :else
                           (reset! loading? true)))}
          [s/FormGroup
           [s/FormInput {:id "create-user-defined-support-level"
                         :value @user-defined-support-level
                         :on-change #(let [value (-> ($ % :target.value) (sutil/ensure-prefix "$"))
                                           validation-error (amount-validation value)]
                                       (if validation-error
                                         (reset! paypal-disabled? true)
                                         (reset! paypal-disabled? false))
                                       (reset! error-message (amount-validation value))
                                       (reset! user-defined-support-level value))
                         :on-click #(reset! support-level :user-defined)}]]
          (when @error-message
            [s/Message {:negative true
                        :onDismiss #(reset! error-message nil)}
             [s/MessageHeader "Payment Error"]
             @error-message])
          (when @success-message
            [s/Message {:positive true
                        :onDismiss #(reset! success-message nil)}
             [s/MessageHeader "Payment Processed"]
             @success-message])
          ;; the built-in PayPal disabled button is weird, the buttons
          ;; simply aren't cickable. This reproduces the same effect
          ;; much simpler
          [:div (when @paypal-disabled? {:style {:pointer-events "none"}})
           [PayPalButton user-defined-support-level
            :on-authorize (fn [data actions]
                            (-> ($ actions payment.execute)
                                ($ then
                                   (fn [response]
                                     ;; we're just going to pass the response to the server
                                     (add-funds state response)
                                     nil))))
            :on-error (fn [err]
                        (reset! error-message "An error was encountered during PayPal checkout"))]]]])
      :get-initial-state
      (fn [this]
        (reset! support-level :user-defined)
        (reset! user-defined-support-level (acct/format-money minimum-amount "$"))
        (reset! loading? false)
        (reset! error-message nil)
        (reset! success-message nil)
        {})})))
