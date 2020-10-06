(ns sysrev.views.panels.org.payment
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.stripe :refer [StripeCardInfo]]
            [sysrev.action.core :refer [run-action]]
            [sysrev.views.semantic :refer [Grid Column Segment Header]]
            [sysrev.views.panels.org.main :as org]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :payment]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(defn OrgPayment [org-id]
  [Grid {:stackable true :columns 2}
   [Column
    [Segment {:secondary true}
     [Header {:as "h1"} "Enter your Payment Method"]
     [StripeCardInfo {:add-payment-fn #(run-action :stripe/add-payment-org org-id %)}]]]])

(def-panel :uri "/org/:org-id/payment" :params [org-id] :panel panel
  :on-route (let [org-id (util/parse-integer org-id)]
              (org/on-navigate-org org-id panel)
              (dispatch [:reload [:org/default-source org-id]]))
  :content (when-let [org-id @(subscribe [::org/org-id])]
             (with-loader [[:org/current-plan org-id]
                           [:org/available-plans org-id]
                           [:org/default-source org-id]] {}
               [OrgPayment org-id]))
  :require-login true)
