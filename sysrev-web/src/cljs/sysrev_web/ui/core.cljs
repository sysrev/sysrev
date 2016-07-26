(ns sysrev-web.ui.core
  (:require [sysrev-web.base :refer [state history server-data debug-box]]
            [sysrev-web.ajax :refer [data-initialized?]]
            [pushy.core :as pushy]
            [reagent.core :as r]
            [cljs.pprint :refer [pprint]]
            [sysrev-web.routes :as routes]
            [sysrev-web.react.components :refer [link]]
            [sysrev-web.ui.home :refer [home]]
            [sysrev-web.ui.login :refer [login]]
            [sysrev-web.ui.user :refer [user]]))


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
(defmethod current-page :login [] (get-page :login login))

(defn user-status []
  (let [user (:user @server-data)]
    (if (nil? user)
      [:div.item
       [link routes/login-route
        [:div.ui.primary.button "Log in"]]]
      [:div.item
       (:name user)])))

(defn main-content []
  [:div
   [:div.ui.container
    [:div.ui.grid
     [:div.ten.wide.column
      [:h1 "Systematic Review"]]
     [:div.six.wide.column
      [:div.ui.right.menu
       [user-status]]]]]
   [:div.main-content
    [current-page]]])

