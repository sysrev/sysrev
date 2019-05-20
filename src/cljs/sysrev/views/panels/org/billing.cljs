(ns sysrev.views.panels.org.billing
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.stripe :as stripe]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]]))

(def ^:private panel [:org :billing])

(def state (r/cursor app-db [:state :panels panel]))

(defn OrgBilling [{:keys [org-id]}]
  (r/create-class
   {:reagent-render
    (fn [this]
      (when (nil? @(subscribe [:current-org]))
        (dispatch [:set-current-org! org-id])
        (dispatch [:fetch [:org-current-plan]]))
      (dispatch [:org/set-on-subscribe-nav-to-url! (str "/org/" @(subscribe [:current-org]) "/billing")])
      [Segment
       [Header {:as "h4" :dividing true}
        "Billing"]
       [ListUI {:divided true :relaxed true}
        [ListItem [Plan {:plans-route "/org/plans"
                         :current-plan-atom (subscribe [:org/current-plan])
                         :fetch-current-plan (fn [] (dispatch [:fetch [:org-current-plan org-id]]))}]]
        [ListItem [PaymentSource {:get-default-source (partial stripe/get-org-default-source org-id)
                                  :default-source-atom (subscribe [:stripe/default-source "org" org-id])
                                  :on-add-payment-method #(do (dispatch [:payment/set-calling-route!
                                                                      (str "/org/" org-id "/billing")])
                                                           (dispatch [:navigate [:org-payment]]))}]]]])}))
