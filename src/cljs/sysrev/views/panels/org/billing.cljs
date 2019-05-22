(ns sysrev.views.panels.org.billing
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.panels.user.billing :refer [Plan PaymentSource]]
            [sysrev.views.semantic :refer [Segment Header ListUI ListItem]]))

(def ^:private panel [:org :billing])

(def state (r/cursor app-db [:state :panels panel]))

(defn OrgBilling [{:keys [org-id]}]
  (let [org-current-plan (subscribe [:org/current-plan])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when (nil? @org-current-plan)
          (dispatch [:fetch [:org-current-plan org-id]]))
        (dispatch [:org/set-on-subscribe-nav-to-url! (str "/org/" org-id "/billing")])
        [Segment
         [Header {:as "h4" :dividing true}
          "Billing"]
         [ListUI {:divided true :relaxed true}
          [ListItem [Plan {:plans-route (str "/org/" org-id "/plans")
                           :current-plan-atom (subscribe [:org/current-plan])
                           :fetch-current-plan (fn [] (dispatch [:fetch [:org-current-plan org-id]]))}]]
          [ListItem [PaymentSource {:get-default-source (partial stripe/get-org-default-source org-id)
                                    :default-source-atom (subscribe [:stripe/default-source "org" org-id])
                                    :on-add-payment-method #(do (dispatch [:payment/set-calling-route!
                                                                           (str "/org/" org-id "/billing")])
                                                                (nav-scroll-top (str "/org/" org-id "/payment")))}]]]])})))
