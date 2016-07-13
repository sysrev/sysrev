(ns sysrev-web.ui.core
    (:require [sysrev-web.base :refer [state history]]
              [sysrev-web.ajax :as ajax]
              [pushy.core :as pushy]
              [reagent.core :as r]
              [cljs.pprint :refer [pprint]]
              [sysrev-web.ui.home :refer [home]]))

(defn show-state []
  [:div {:style {:background-color "lightgrey"}}
   [:h1 "State"]
   [:pre (with-out-str (pprint @state))]])   


;; Not working at the moment...
;; should be able to select page open based on route set in routes.cljs.
(defmulti current-page #(@state :page))
(defmethod current-page :home []  [home state])



(defn main-content []
  [:div.main-content
   [home state]
   [show-state]])


