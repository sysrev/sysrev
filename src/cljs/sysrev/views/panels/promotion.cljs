(ns sysrev.views.panels.promotion
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.macros :refer-macros [def-panel setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:promotion])

(defn- PromotionExpired []
  [:div.ui.center.aligned.segment>h1.ui.center.aligned.header
   "This Promotion has expired"])

(defn- Panel []
  [PromotionExpired])

(def-panel :uri "/promotion" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content [Panel])
