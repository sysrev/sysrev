(ns sysrev.views.panels.org.payment
  (:require [re-frame.core :refer [dispatch subscribe]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer [Grid Column Segment Header]]))

(defmethod logged-out-content [:org-payment] []
  (logged-out-content :logged-out))

(defmethod panel-content [:org-payment] []
  (fn [child]
    [Grid
     [Column {:width 8}
      [Segment {:secondary true}
       [Header {:as "h1"} "Enter your Payment Method"]
       [StripeCardInfo {:add-payment-fn
                        (fn [payload]
                          (dispatch [:action [:stripe/add-payment-org @(subscribe [:current-org]) payload]]))}]]]]))
