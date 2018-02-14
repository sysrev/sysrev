(ns sysrev.views.panels.payment
  (:require [re-frame.core :refer [reg-event-db trim-v]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.views.base :refer [panel-content]]
            ))

;; should be of the form [:route]
(reg-event-db
 :payment/set-calling-route!
 [trim-v]
 (fn [db [calling-route]]
   (assoc-in db [:state :stripe :calling-route] calling-route)))

(defmethod panel-content [:payment] []
  (fn [child]
    [:div {:class "ui two columns stackable grid"}
     [:div {:class "column"}
      [:div {:class "ui segment secondary"}
       [StripeCardInfo]]]]))
