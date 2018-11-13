(ns sysrev.paypal
  (:require [ajax.core :refer [POST]]
            [cljsjs.accounting]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.views.semantic :refer [Form FormGroup FormInput Message MessageHeader]])
  (:require-macros [reagent.interop :refer [$]]))

(def panel [:project :project :paypal])

(def state (r/cursor app-db [:state :panel panel]))

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
           :handler (fn [response]
                      (reset! loading? false)
                      (dispatch [:project/get-funds])
                      (reset! success-message
                              (str "You've added " @user-defined-support-level " to your project funds!"))
                      (reset! user-defined-support-level "$1.00"))
           :error-handler (fn [error]
                            (reset! loading? false)
                            (reset! error-message (get-in error [:response :error :message])))})))

;; https://developer.paypal.com/docs/checkout/quick-start/
;; this also depends on [:script {:src "https://www.paypalobjects.com/api/checkout.js"}]
;; being in index.clj

;; test information associated with:  james+sandbox+1@insilica.co
;; visa number: 4032033511927936
;; exp: 11/2023
;; everything else you can make up, except the city must match the zip code
(defn PayPalButton
  "Create a PayPalButton. amount-atom derefs to an integer amount of cents"
  [amount-atom]
  (r/create-class
   {:reagent-render
    (fn [this]
      [:div {:id "paypal-button-container"}])
    :component-did-mount
    (fn [this]
      (-> ($ js/paypal :Button)
          ($ render
             (clj->js
              {:env "sandbox"
               :style {:layout "vertical"
                       :size "medium"
                       :shape "rect"
                       :color "gold"}
               :funding {:allowed [($ js/paypal :FUNDING.CARD)
                                   ($ js/paypal :FUNDING.CREDIT)]
                         :disallowed []}
               :client {:sandbox "AYwD1bq9SVBK1fscFav9zfKkU6DDiPLM648f-9DvX-tuvmiGfh6ItkvQEZE-x6ugT2iSQvZf5ToGv8rj"
                        :production "Ac4_oBSNUAPpYAQgAjGgoeI8T3V6VitePP5KoMJMK-07whbcYzUZa_4FTy2YwTJoZgNIyIWhH-QbOveD"}
               :payment (fn [data actions]
                          ($ actions payment.create
                             (clj->js
                              {:payment
                               {:transactions [{:amount {:total ($ js/accounting formatMoney @amount-atom "")
                                                         :currency "USD"}}]}
                               ;; what other experience fields can we set?
                               :experience {:input_fields {:no_shipping 1}}})))
               :onAuthorize (fn [data actions]
                              (-> ($ actions payment.execute)
                                  ($ then (fn [response]
                                            ;; we're just going to pass the response to the server
                                            (add-funds state response)
                                            nil))))})
             "#paypal-button-container")))}))

(defn AddFunds
  [state]
  (let [support-level (r/cursor state [:support-level])
        user-defined-support-level (r/cursor state [:user-defined-support-level])
        error-message (r/cursor state [:error-message])
        loading? (r/cursor state [:loading?])
        success-message (r/cursor state [:success-message])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div.ui.segment
         [:h4.ui.dividing.header "Add Funds"]
         [Form {:on-submit
                (fn []
                  (let [cents (accounting/string->cents @user-defined-support-level)]
                    (cond (and (= @support-level
                                  :user-defined)
                               (= cents
                                  0))
                          (reset! user-defined-support-level "$0.00")
                          (and (= @support-level
                                  :user-defined)
                               (> cents
                                  0))
                          (do
                            (reset! loading? true))
                          :else
                          (do
                            (reset! loading? true)))))}
          [FormGroup
           (let [on-change (fn [event]
                             (let [value ($ event :target.value)
                                   dollar-sign-on-front? (fn [value]
                                                           (= ($ value indexOf "$")
                                                              0))
                                   new-value (cond
                                               (not (dollar-sign-on-front? value))
                                               (str "$" ($ event :target.value))
                                               :else
                                               value)]
                               (reset! user-defined-support-level new-value)))]
             [FormInput {:value @user-defined-support-level
                         :on-change on-change
                         :id "create-user-defined-support-level"
                         :on-click #(reset! support-level :user-defined)}])]
          (when @error-message
            [Message {:onDismiss #(reset! error-message nil)
                      :negative true}
             [MessageHeader "Transfer Error"]
             @error-message])
          (when @success-message
            [Message {:onDismiss #(reset! success-message nil)
                      :positive true}
             [MessageHeader "Transfer Successful"]
             @success-message])
          [PayPalButton user-defined-support-level]]])
      :get-initial-state
      (fn [this]
        (reset! support-level :user-defined)
        (reset! user-defined-support-level "$1.00")
        (reset! loading? false)
        (reset! error-message nil)
        {})})))
