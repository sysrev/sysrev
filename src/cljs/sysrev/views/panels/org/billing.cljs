(ns sysrev.views.panels.org.billing
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]])
  (:require-macros [sysrev.macros :refer [setup-panel-state]]))

(setup-panel-state panel [:org :billing] {:state-var state})

(defn OrgBilling [{:keys [org-id]}]
  (when org-id
    (dispatch [:require [:org-current-plan org-id]])
    (dispatch [:org/set-on-subscribe-nav-to-url! org-id (str "/org/" org-id "/billing")])
    [Segment
     [Header {:as "h4" :dividing true} "Billing"]
     [ListUI {:divided true :relaxed true}
      [ListItem
       [Plan
        {:plans-route (str "/org/" org-id "/plans")
         :current-plan-atom (subscribe [:org/current-plan org-id])
         :fetch-current-plan #(dispatch [:reload [:org-current-plan org-id]])}]]
      [ListItem
       [PaymentSource
        {:get-default-source (partial stripe/get-org-default-source org-id)
         :default-source-atom (subscribe [:stripe/default-source "org" org-id])
         :on-add-payment-method #(do (dispatch [:payment/set-calling-route!
                                                (str "/org/" org-id "/billing")])
                                     (nav-scroll-top (str "/org/" org-id "/payment")))}]]]]))
