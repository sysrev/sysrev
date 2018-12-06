(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET POST PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.panels.plans :refer [UserPlans]]
            [sysrev.views.semantic :refer [Grid Row Column Button ListUI Item Segment Header Icon]]))

(def state (r/cursor app-db [:state :panels :user :billing]))

(defn get-default-source
  [state]
  (let [default-source (r/cursor state [:default-source])
        default-source-error (r/cursor state [:default-source-error])
        user-id @(subscribe [:self/user-id])]
    (GET (str "/api/user/" user-id "/stripe/default-source")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                        (reset! default-source (-> response :result :default-source)))
          :error-handler (fn [error-response]
                           (reset! default-source-error (get-in error-response [:response :error :message])))})))
(defn PaymentSource
  []
  (let [default-source (r/cursor state [:default-source])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Item
         [Grid {:stackable true}
          [Row
           [Column {:computer 2}
            "Payment"]
           [Column {:computer 8}
            [:div {:style {:font-weight "bold"}}
             [Icon {:name "credit card"}]
             (if @default-source
               (let [{:keys [brand exp_month exp_year last4]}
                     @default-source]
                 (str brand " expiring on " exp_month "/" exp_year " and ending in " last4))
               "No payment method on file.")]]
           [Column {:computer 6
                    :align "right"}
            [Button {:on-click (fn [event]
                                 ;;(.log js/console "add payment method today!")
                                 (dispatch [:payment/set-calling-route! (str "/user/settings/billing")])
                                 (dispatch [:navigate [:payment]]))}
             (if @default-source
               "Change payment method"
               "Add payment method")]]]]])
      :get-initial-state
      (fn [this]
        (get-default-source state))})))

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
