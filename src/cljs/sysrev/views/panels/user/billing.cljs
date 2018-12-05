(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET POST PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.panels.plans :refer [UserPlans]]
            [sysrev.views.semantic :refer [Grid Row Column Button ListUI Item Segment Header]]))

(def state (r/cursor app-db [:state :panels :user :billing]))

(defn get-payment-source
  [state]
  (let [payment-source? (r/cursor state [:payment-source?])
        payment-source-error (r/cursor state [:payment-source-error])
        user-id @(subscribe [:self/user-id])]
    (GET (str "/api/user/" user-id "/stripe/payment-source")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                        (reset! payment-source? (-> response :result :payment-source)))
          :error-handler (fn [error-response]
                           (reset! payment-source-error (get-in error-response [:response :error :message])))})))
(defn PaymentSource
  []
  (let [payment-source? (r/cursor state [:payment-source?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Item
         [Grid
          [Row
           [Column {:width 2}
            "Payment"]
           [Column {:width 7}
            (if @payment-source?
              ""
              "No payment method on file.")]
           [Column {:width 7
                    :align "right"}
            (if @payment-source?
              ""
              [Button {:on-click (fn [event]
                                   (.log js/console "add payment method today!"))}
               "Add payment method"])]]]])
      :get-initial-state
      (fn [this]
        (get-payment-source state))})))

(defn Billing
  []
  [Segment
   [Header {:as "h4"
            :dividing true}
    "Billing"]
   [ListUI {:divided true
            :relaxed true}
    [PaymentSource]]
   [UserPlans]])
