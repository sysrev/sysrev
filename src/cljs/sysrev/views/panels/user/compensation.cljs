(ns sysrev.views.panels.user.compensation
  (:require [ajax.core :refer [POST GET DELETE PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx reg-sub trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.util :as u]
            [sysrev.views.semantic :as s :refer
             [Grid Row Column Segment Header]]))

(def ^:private panel [:user :compensation])

(def state (r/cursor app-db [:state :panels panel]))

(reg-sub :compensation/payments-owed
         (fn [db] @(r/cursor state [:payments-owed])))

(reg-sub :compensation/payments-paid
         (fn [db] @(r/cursor state [:payments-paid])))

(defn get-payments-owed!
  "Retrieve the current amount of compensation owed to user"
  []
  (let [user-id @(subscribe [:self/user-id])
        payments-owed (r/cursor state [:payments-owed])
        retrieving-payments-owed? (r/cursor state [:retrieving-payments-owed?])
        error-message (r/cursor state [:retrieving-payments-error-message])]
    (reset! retrieving-payments-owed? true)
    (GET (str "/api/user/" user-id "/payments-owed")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-payments-owed? false)
                     (reset! payments-owed (get-in response [:result :payments-owed])))
          :error-handler (fn [error-response]
                           (reset! retrieving-payments-owed? false)
                           (reset! error-message (get-in error-response [:response :error :message])))})))

(reg-event-fx
 :compensation/get-payments-owed!
 [trim-v]
 (fn [_ _]
   (get-payments-owed!)
   {}))

(defn get-payments-paid!
  "Retrieve the current amount of compensation made to user"
  []
  (let [user-id @(subscribe [:self/user-id])
        payments-paid (r/cursor state [:payments-paid])
        retrieving-payments-paid? (r/cursor state [:retrieving-payments-paid?])
        error-message (r/cursor state [:retrieving-payments-error-message])]
    (reset! retrieving-payments-paid? true)
    (GET (str "/api/user/" user-id "/payments-paid")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-payments-paid? false)
                     (reset! payments-paid (get-in response [:result :payments-paid])))
          :error-handler (fn [error-response]
                           (reset! retrieving-payments-paid? false)
                           (reset! error-message (get-in error-response [:response :error :message])))})))

(reg-event-fx
 :compensation/get-payments-paid!
 [trim-v]
 (fn [_ _]
   (get-payments-paid!)
   {}))

(defn PaymentOwed
  "Display a payment-owed map"
  [{:keys [total-owed project-id project-name]}]
  ^{:key (gensym project-id)}
  [s/ListItem
   [Grid
    [Row
     [Column {:width 5}
      project-name]
     [Column {:width 8}]
     [Column {:width 3
              :align "right"}
      (accounting/cents->string total-owed)]]]])

(defn PaymentsOwed
  []
  (let [payments-owed (r/cursor state [:payments-owed])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Segment
         [Header {:as "h4" :dividing true} "Payments Owed"]
         (if-not (empty? @payments-owed)
           [s/ListUI {:divided true :relaxed true}
            (doall (map
                    (partial PaymentOwed)
                    @payments-owed))]
           [:div [:h4 "You do not currently have any payments owed"]])])
      :component-did-mount
      (fn [this]
        (get-payments-owed!))})))

(defn PaymentPaid
  "Display a payment-owed map"
  [{:keys [total-paid project-id project-name created]}]
  ^{:key (gensym project-id)}
  [s/ListItem
   [Grid
    [Row
     [Column {:width 5} project-name]
     [Column {:width 8}
      (str "Paid on: " (u/unix-epoch->date-string created)) ]
     [Column {:width 3 :align "right"}
      (accounting/cents->string total-paid)]]]])

(defn PaymentsPaid []
  (let [payments-paid (r/cursor state [:payments-paid])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when-not (empty? @payments-paid)
          [Segment
           [Header {:as "h4" :dividing true}
            "Payments Paid"]
           [s/ListUI {:divided true :relaxed true}
            (doall (map (partial PaymentPaid) @payments-paid))]]))
      :component-did-mount
      (fn [this]
        (get-payments-paid!))})))
