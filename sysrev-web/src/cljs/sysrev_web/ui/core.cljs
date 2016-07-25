(ns sysrev-web.ui.core
  (:require [sysrev-web.base :refer [state history server-data debug-box]]
            [sysrev-web.ajax :refer [data-initialized?]]
            [pushy.core :as pushy]
            [reagent.core :as r]
            [cljs.pprint :refer [pprint]]
            [sysrev-web.ui.home :refer [home]]
            [sysrev-web.ui.user :refer [user]]))

;; Not working at the moment...
;; should be able to select page open based on route set in routes.cljs.


(defn loading-screen []
  (fn []
    [:div.ui.container
     [:div.ui.stripe {:style {:padding-top "20px"}}
      [:h1.ui.header.huge.center.aligned "Loading data..."]]]))

(defn page-container [page]
  (fn [page]
    [:div.ui.grid.container
     [:div.row
      page]]))


(defn get-page [key r]
  (if (data-initialized? key)
    [page-container [r]]
    [loading-screen]))

(defmulti current-page (fn [] (:page @state)))
(defmethod current-page :home []  (get-page :home home))
(defmethod current-page :user [] (get-page :user user))


(defn main-content []
  [:div.main-content
   [current-page]])
