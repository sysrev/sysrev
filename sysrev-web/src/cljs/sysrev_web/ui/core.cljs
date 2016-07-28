(ns sysrev-web.ui.core
  (:require [sysrev-web.base :refer [state history server-data debug-box]]
            [sysrev-web.routes :as routes :refer [data-initialized? post-login post-register]]
            [sysrev-web.ui.containers :refer [loading-screen page-container get-page center-page]]
            [sysrev-web.react.components :refer [link link-nonav]]
            [sysrev-web.ui.home :refer [home]]
            [sysrev-web.ui.login :refer [login]]
            [sysrev-web.ui.user :refer [user]]))

(defn login-page [handler] (center-page [:h1 "Login"] [login handler]))
(defn register-page [handler] (center-page [:h1 "Register"] [login handler]))


(defmulti current-page (fn [] (:page @state)))
(defmethod current-page :home []  (get-page :home home))
(defmethod current-page :user [] (get-page :user user))
(defmethod current-page :login [] (get-page :login login-page post-login))
(defmethod current-page :register [] (get-page :register register-page post-register))

(defn user-status [{:keys [class]}]
  (fn []
    (let [user (:user @server-data)]
      (if (nil? user)
        [:div.ui.menu {:class class}
         [:div.item
          [link routes/login
           [:div.ui.primary.button "Log in"]]]
         [:div.item
          [link routes/register
           [:div.ui.primary.button "Register"]]]]
        [:div.ui.menu {:class class}
         [:div.item
          (str "Welcome " (:name user))]
         [:div.item
          [link-nonav routes/post-logout
           [:div.ui.primary.button "Logout"]]]]))))

(defn main-content []
  (fn []
    [:div
     [:div.ui.container
      [:div.ui.grid
       [:div.ten.wide.column
        [link routes/home [:h1 "Systematic Review"]]]
       [:div.six.wide.column
         [user-status]]]]
     [:div.main-content
      [current-page]]]))
