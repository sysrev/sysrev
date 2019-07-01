(ns sysrev.views.panels.user.payment
  (:require [re-frame.core :refer [subscribe dispatch reg-event-db trim-v]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer [Grid Row Column Header Segment]]))

(defmethod panel-content [:payment] []
  (fn [child]
    (when-let [user-id @(subscribe [:self/user-id])]
      [Grid
       [Column {:width 8}
        [Segment {:secondary true}
         [Header {:as "h1"} "Enter your Payment Method"]
         [StripeCardInfo {:add-payment-fn
                          (fn [payload]
                            (dispatch [:action [:stripe/add-payment-user user-id payload]]))}]]]])))

(defmethod logged-out-content [:payment] []
  (logged-out-content :logged-out))
