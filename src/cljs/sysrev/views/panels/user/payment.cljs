(ns sysrev.views.panels.user.payment
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.views.semantic :refer [Grid Column Header Segment]]
            [sysrev.macros :refer-macros [def-panel]]))

(def panel [:payment])

(defn- Panel []
  (when-let [user-id @(subscribe [:self/user-id])]
    [Grid
     [Column {:width 8}
      [Segment {:secondary true}
       [Header {:as "h1"} "Enter your Payment Method"]
       [StripeCardInfo {:add-payment-fn
                        (fn [payload]
                          (dispatch [:action [:stripe/add-payment-user user-id payload]]))}]]]]))

(def-panel :uri "/user/payment" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content [Panel]
  :require-login true)
