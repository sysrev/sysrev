(ns sysrev.views.panels.org.payment
  (:require [re-frame.core :refer [dispatch subscribe]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer [Grid Column Segment Header]]))

(defn OrgPayment
  [{:keys [org-id]}]
  [Grid {:stackable true :columns 2}
   [Column
    [Segment {:secondary true}
     [Header {:as "h1"} "Enter your Payment Method"]
     [StripeCardInfo {:add-payment-fn
                      (fn [payload]
                        (dispatch [:action [:stripe/add-payment-org org-id payload]]))}]]]])
