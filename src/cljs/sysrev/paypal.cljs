(ns sysrev.paypal
  (:require [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))


;; https://developer.paypal.com/docs/checkout/quick-start/
;; this also depends on [:script {:src "https://www.paypalobjects.com/api/checkout.js"}]
;; being in index.clj

(defn PayPalButton
  []
  (r/create-class
   {:reagent-render
    (fn [this] [:div {:id "paypal-button-container"}])
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
                               {:transactions [{:amount {:total "0.01"
                                                         :currency "USD"}}]}})))
               :onAuthorize (fn [data actions]
                              (-> ($ actions payment.execute)
                                  ($ then (fn []
                                            ($ js/window alert "Payment Complete!")))))})
             "#paypal-button-container")))}))
