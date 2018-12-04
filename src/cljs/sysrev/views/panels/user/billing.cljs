(ns sysrev.views.panels.user.billing
  (:require [reagent.core :as r]
            [re-frame.db :refer [app-db]]))

(def state (r/cursor app-db [:state :panels :user :billing]))

(defn Plans
  []
  [:div.ui.segment
   [:h4.ui.dividing.header
    "Billing"]
   [:h1 "Billing goes here"]])
