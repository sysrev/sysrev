(ns sysrev-web.ui.core
  (:require
   [sysrev-web.base :refer [state server-data current-page on-page? logged-in?]]
   [sysrev-web.routes :refer [data-initialized?]]
   [sysrev-web.notify :refer [notify-head]]
   [sysrev-web.ui.components :refer [loading-screen notifier]]
   [sysrev-web.ui.users :refer [users-page]]
   [sysrev-web.ui.labels :refer [labels-page]]
   [sysrev-web.ui.login :refer [login-page register-page]]
   [sysrev-web.ui.user-profile :refer [user-profile-page]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.classify]))

(defn current-page-content []
  (if-not (data-initialized? (current-page))
    [loading-screen]
    (cond (on-page? :users) (users-page)
          (on-page? :labels) (labels-page)
          (on-page? :login) (login-page)
          (on-page? :register) (register-page)
          (on-page? :user-profile) (user-profile-page)
          true [:div "Route not found"])))

(defn menu-link
  ([route-or-action attributes content]
   (if (string? route-or-action)
     [:a.ui.link (merge {:href route-or-action} attributes)
      content]
     [:a.ui.link (merge {:on-click route-or-action} attributes)
      content]))
  ([route-or-action content]
   (menu-link route-or-action {:class "item"} content)))

(defn logged-in-menu []
  (let [ident (-> @state :identity)
        uid (:id ident)
        email (:email ident)
        name (:name ident)
        display-name (or name email)]
    [:div.ui.menu.right.floated
     [:div.item
      [:div.content
       [:div.header "Welcome"]
       [:div.description
        [:a.ui.link {:href (str "/user/" uid)}
         display-name]]]]
     [menu-link "/users" "Users"]
     [menu-link "/labels" "Labels"]
     [menu-link "/classify" "Classify"]
     [menu-link ajax/post-logout "Logout"]]))

(defn logged-out-menu []
  [:div.ui.menu.right.floated
   [:div.item
    [:a.ui.link {:href "/login"}
     [:div.ui.primary.button "Log in"]]]
   [:div.item
    [:a.ui.link {:href "/register"}
     [:div.ui.primary.button "Register"]]]])

(defn menu-component []
  (fn []
    (if (logged-in?)
      [logged-in-menu]
      [logged-out-menu])))

(defn page-container [content]
  (fn [content]
    [:div
     [:div.ui.container
      [:div.ui.grid
       [:div.middle.aligned.row
        [:div.ui.middle.aligned.four.wide.column
         [:a.ui.link {:href "/"}
          [:h1 "Systematic Review"]]]
        [:div.ui.right.floated.left.aligned.twelve.wide.column
         [menu-component]]]
       [:div.middle.aligned.row
        [:div.main-content.sixteen.wide.column
         content]]]]
     [notifier (notify-head) 2000]]))

(defn main-content []
  (fn []
    (let [content (current-page-content)]
      [page-container content])))
