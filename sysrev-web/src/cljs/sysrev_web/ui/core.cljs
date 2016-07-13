(ns sysrev-web.ui.core
  (:require [sysrev-web.base :refer [state history server-data]]
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
   (if (not (ajax/data-initialized? :home))
     [:div.ui.container
      [:div.ui.stripe {:style {:padding-top "20px"}}
       [:h1.ui.header.huge.center.aligned "Loading data..."]]]
     [:div.ui.grid.container
      [:div.row
       [home]]
      [:div.row
       [show-state]]])])
