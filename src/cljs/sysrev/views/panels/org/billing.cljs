(ns sysrev.views.panels.org.billing
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
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
        [ListItem [PaymentSource]]]])}))
