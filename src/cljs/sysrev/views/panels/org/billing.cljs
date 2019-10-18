(ns sysrev.views.panels.org.billing
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

(setup-panel-state panel [:org :billing] {:state-var state})

(defn OrgBilling [{:keys [org-id]}]
  (when org-id
    (dispatch [:data/load [:org/default-source org-id]])
    (dispatch [:data/load [:org/current-plan org-id]])
    (dispatch [:org/set-on-subscribe-nav-to-url! org-id (str "/org/" org-id "/billing")])
    #_ (js/console.log "OrgBilling: current-plan = " (:name @(subscribe [:org/current-plan org-id])))
    #_ (js/console.log "OrgBilling: default-source = " (str @(subscribe [:org/default-source org-id])))
    [Segment
     [Header {:as "h4" :dividing true} "Billing"]
     [ListUI {:divided true :relaxed true}
      [ListItem
       [Plan {:plans-url (str "/org/" org-id "/plans")
              :current-plan @(subscribe [:org/current-plan org-id])}]]
      [ListItem
       [PaymentSource
        {:default-source @(subscribe [:org/default-source org-id])
         :on-add-payment-method
         (fn []
           (dispatch [:stripe/set-calling-route! (str "/org/" org-id "/billing")])
           (nav-scroll-top (str "/org/" org-id "/payment")))}]]]]))
