(ns sysrev.views.panels.org.billing
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :billing] {:state-var state})

(defn OrgBilling [{:keys [org-id]}]
  (when org-id
    (dispatch [:data/load [:org/default-source org-id]])
    (dispatch [:data/load [:org/current-plan org-id]])
    [Segment
     [Header {:as "h4" :dividing true} "Billing"]
     [ListUI {:divided true :relaxed true}
      [ListItem
       [Plan {:plans-url (str "/org/" org-id "/plans")
              :current-plan @(subscribe [:org/current-plan org-id])}]]
      [ListItem
       [PaymentSource
        {:default-source (subscribe [:org/default-source org-id])
         :change-source-fn (fn [payload]
                             (dispatch [:action [:stripe/add-payment-org org-id payload]]))}]]]]))
