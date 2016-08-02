(ns sysrev-web.ui.core
  (:require [sysrev-web.base :refer [state history server-data debug-box notify-pop notify-head]]
            [sysrev-web.routes :as routes :refer [data-initialized? post-login post-register]]
            [sysrev-web.ui.containers :refer [loading-screen page-container get-page center-page]]
            [sysrev-web.react.components :refer [link link-nonav]]
            [sysrev-web.ui.home :refer [home]]
            [sysrev-web.ui.login :refer [login]]
            [sysrev-web.ui.user :refer [user]]
            [sysrev-web.ui.users :refer [users]]
            [sysrev-web.ui.classify :refer [classify]]
            [sysrev-web.ui.labels :refer [labels]]
            [sysrev-web.ui.notification :refer [notifier]]))

(def notification-timeout 3000)
(defn page-notifier [head] (notifier head notify-pop notification-timeout))


(defn login-page [handler] (center-page [:h1 "Login"] [login handler]))
(defn register-page [handler] (center-page [:h1 "Register"] [login handler]))

;; Route resolving function, to construct the proper page based on the page accessed.
(defmulti current-page (fn [] (:page @state)))
(defmethod current-page :home []  (get-page :home home))
(defmethod current-page :user [] (get-page :user user))
(defmethod current-page :login [] (get-page :login login-page post-login))
(defmethod current-page :register [] (get-page :register register-page post-register))
(defmethod current-page :users [] (get-page :users users))
(defmethod current-page :classify [] (get-page :classify classify))
(defmethod current-page :labels [] (get-page :labels labels))

(defn menu-link
  ([f attributes content] (link attributes content))
  ([f content] (link {:class "item"} f content)))
(defn menu-link-nonav [f content] (link {:class "item"} f content))

(defn logged-in-menu [{:keys [class]} user]
  (let [uid (:id user)
        user (:user user)]
    [:div.ui.menu {:class class}
     [:div.item
      [:div.content
       [:div.header "Welcome"]
       [:div.description
        [link #(routes/user {:id uid})
         (if (nil? (:name user))
             (:username user)
             (:name user))]]]]
     [menu-link routes/users "Users"]
     [menu-link routes/labels "Labels"]
     [menu-link routes/classify "Classify"]
     [menu-link-nonav routes/post-logout "Logout"]]))



(defn logged-out-menu [{:keys [class]}]
  [:div.ui.menu {:class class}
   [:div.item
    [link routes/login
     [:div.ui.primary.button "Log in"]]]
   [:div.item
    [menu-link routes/register "Register"]]])

;; Login dependent upper menu
(defn user-status [{:keys [class]}]
  (fn []
    (let [user (:user @server-data)]
      (if (nil? user)
        [logged-out-menu {:class class}]
        [logged-in-menu {:class class} user]))))

;;"Main container for all dom elements. Includes page from above current-page to
;; select content based on accessed route."
(defn main-content []
  (fn []
    [:div
     [:div.ui.container
      [:div.ui.grid
       [:div.middle.aligned.row
        [:div.ui.middle.aligned.four.wide.column
         [link routes/home [:h1 "Systematic Review"]]]
        [:div.ui.right.floated.left.aligned.twelve.wide.column
         [user-status {:class "right floated"}]]]
       [:div.row
        [:div.main-content
         [current-page]]]]]
     [page-notifier (notify-head)]]))
