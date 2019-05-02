(ns sysrev.views.panels.org.billing
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.stripe :as stripe]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]]))

(def ^:private panel [:org :billing])

(def state (r/cursor app-db [:state :panels panel]))

(defn OrgBilling []
  (r/create-class
   {:reagent-render
    (fn [this]
      (when-not @(subscribe [:current-org])
        (dispatch [:fetch [:org-current-plan]]))
      [Segment
       [Header {:as "h4" :dividing true}
        "Billing"]
       [ListUI {:divided true :relaxed true}
        [ListItem [Plan {:plans-route "/org/plans"
                         :current-plan-atom (subscribe [:org/current-plan])
                         :fetch-current-plan (fn [] (dispatch [:fetch [:org-current-plan]]))}]]
        [ListItem [PaymentSource {:get-default-source (partial stripe/get-org-default-source @(subscribe [:current-org]))
                                  :default-source (subscribe [:stripe/default-source "org" @(subscribe [:current-org])])
                                  :add-payment-method #(do (dispatch [:payment/set-calling-route! "/org/billing"])
                                                           (dispatch [:navigate [:org-payment]]))}]]]])}))
