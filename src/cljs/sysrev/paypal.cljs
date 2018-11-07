(ns sysrev.paypal
  (:require [cljsjs.accounting]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.views.semantic :refer [Form FormGroup FormInput]])
  (:require-macros [reagent.interop :refer [$]]))

(def panel [:project :project :paypal])

(def state (r/cursor app-db [:state :panel panel]))

;; https://developer.paypal.com/docs/checkout/quick-start/
;; this also depends on [:script {:src "https://www.paypalobjects.com/api/checkout.js"}]
;; being in index.clj

(defn PayPalButton
  [state]
  (let [support-level (r/cursor state [:support-level])
        user-defined-support-level (r/cursor state [:user-defined-support-level])
        error-message (r/cursor state [:error-message])
        loading? (r/cursor state [:loading?])]
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
            [:div.ui.red.header @error-message])
          [:div {:id "paypal-button-container"}]]])
      :get-initial-state
      (fn [this]
        (reset! support-level :user-defined)
        (reset! user-defined-support-level "$1.00")
        (reset! loading? false)
        (reset! error-message "")
        {})
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
                                 {:transactions [{:amount {:total ($ js/accounting formatMoney @user-defined-support-level "")
                                                           :currency "USD"}}]}})))
                 :onAuthorize (fn [data actions]
                                (-> ($ actions payment.execute)
                                    ($ then (fn []
                                              ($ js/window alert "Payment amount:"
                                                 ($ js/accounting formatMoney @user-defined-support-level "$"))))))})
               "#paypal-button-container")))})))
