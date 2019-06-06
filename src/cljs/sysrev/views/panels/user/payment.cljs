(ns sysrev.views.panels.user.payment
  (:require [re-frame.core :refer [reg-event-db trim-v dispatch]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer [Grid Row Column Header Segment]]))

;; should be of the form [:route]
(reg-event-db
 :payment/set-calling-route!
 [trim-v]
 (fn [db [calling-route]]
   (assoc-in db [:state :stripe :calling-route] calling-route)))

(defmethod logged-out-content [:payment] []
  (logged-out-content :logged-out))

(defmethod panel-content [:payment] []
  (fn [child]
    [Grid
     [Column {:width 8}
      [Segment {:secondary true}
       [Header {:as "h1"} "Enter your Payment Method"]
       [StripeCardInfo {:add-payment-fn
                        (fn [payload]
                          (dispatch [:action [:stripe/add-payment-user payload]]))}]]]]))
