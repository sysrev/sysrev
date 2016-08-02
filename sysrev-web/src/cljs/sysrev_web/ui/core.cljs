(ns sysrev-web.ui.core
  (:require [sysrev-web.base :refer [state history server-data debug-box]]
            [sysrev-web.routes :as routes :refer [data-initialized? post-login post-register]]
            [sysrev-web.ui.containers :refer [loading-screen page-container get-page center-page]]
            [sysrev-web.react.components :refer [link link-nonav]]
            [sysrev-web.ui.home :refer [home]]
            [sysrev-web.ui.login :refer [login]]
            [sysrev-web.ui.user :refer [user]]
            [sysrev-web.ui.users :refer [users]]))


(defn login-page [handler] (center-page [:h1 "Login"] [login handler]))
(defn register-page [handler] (center-page [:h1 "Register"] [login handler]))

;; Route resolving function, to construct the proper page based on the page accessed.
(defmulti current-page (fn [] (:page @state)))
(defmethod current-page :home []  (get-page :home home))
(defmethod current-page :user [] (get-page :user user))
(defmethod current-page :login [] (get-page :login login-page post-login))
(defmethod current-page :register [] (get-page :register register-page post-register))
(defmethod current-page :users [] (get-page :users users))

;; Login dependent upper menu
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



(defn main-content
  "Main container for all dom elements. Includes page from above current-page to
  select content based on accessed route."
  []
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
