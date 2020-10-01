(ns sysrev.views.panels.org.billing
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :refer [run-action]]
            [sysrev.views.panels.org.main :as org]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :billing] {:state-var state
                                          :get-fn panel-get :set-fn panel-set
                                          :get-sub ::get    :set-event ::set})

(defn OrgBilling [org-id]
  [Segment
   [Header {:as "h4" :dividing true} "Billing"]
   [ListUI {:divided true :relaxed true}
    [ListItem
     [Plan {:plans-url (str "/org/" org-id "/plans")
            :current-plan @(subscribe [:org/current-plan org-id])}]]
    [ListItem
     [PaymentSource
      {:default-source (subscribe [:org/default-source org-id])
       :change-source-fn #(run-action :stripe/add-payment-org org-id %)}]]]])

(def-panel {:uri "/org/:org-id/billing" :params [org-id]
            :on-route (let [org-id (util/parse-integer org-id)]
                        (org/on-navigate-org org-id panel)
                        (dispatch [:reload [:org/default-source org-id]]))
            :panel panel
            :content (when-let [org-id @(subscribe [::org/org-id])]
                       (with-loader [[:org/current-plan org-id]
                                     [:org/available-plans org-id]
                                     [:org/default-source org-id]] {}
                         [OrgBilling org-id]))
            :require-login true})
