(ns sysrev-web.ui.core
  (:require
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.core :refer [current-page on-page? logged-in?]]
   [sysrev-web.routes :refer [data-initialized? on-public-page?]]
   [sysrev-web.notify :refer [notify-head]]
   [sysrev-web.ui.components :refer [loading-screen notifier]]
   [sysrev-web.ui.sysrev :refer [project-page]]
   [sysrev-web.ui.labels :refer [labels-page]]
   [sysrev-web.ui.login :refer [login-register-page]]
   [sysrev-web.ui.user-profile :refer [user-profile-page]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.ui.classify :refer [classify-page]]
   [sysrev-web.ui.article-page :refer [article-page]]
   [reagent.core :as reagent]))

(defn logged-out-content []
  [:div.ui.container
   [:div.ui.stripe {:style {:padding-top "20px"}}
    [:h2.ui.header.huge.center.aligned "Please log in or register"]]])

(defn current-page-content []
  (cond (not (data-initialized? (current-page))) [loading-screen]
        (and (not (logged-in?))
             (not (on-public-page?))) [logged-out-content]
        (on-page? :project) [project-page]
        (on-page? :labels) [labels-page]
        (on-page? :login) [login-register-page false]
        (on-page? :register) [login-register-page true]
        (on-page? :user-profile) [user-profile-page]
        (on-page? :classify) [classify-page]
        (on-page? :article) [article-page]
        true [:div "Route not found"]))

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
     [menu-link (str "/user/" uid)
      {:class "item"
       :style {:color "rgba(64, 121, 191, 1)"}}
      [:div
       [:i.user.icon]
       display-name]]
     [menu-link "/project" "Project"]
     [menu-link "/labels" "Labels"]
     [menu-link "/classify" "Classify"]
     [:div.item
      [:a.ui.button {:on-click ajax/do-post-logout}
       "Log out"]]]))

(defn logged-out-menu []
  [:div.ui.menu.right.floated
   [menu-link "/project" "Project"]
   [menu-link "/labels" "Labels"]
   [:div.item
    [:a.ui.button {:href "/login"}
     "Log in"]]
   [:div.item
    [:a.ui.button {:href "/register"}
     "Register"]]])

(defn menu-component []
  (if (logged-in?)
    [logged-in-menu]
    [logged-out-menu]))

(defn main-content []
  [:div
   [:div.ui.container.main-content
    [:div.ui.grid
     [:div.middle.aligned.row
      [:div.ui.middle.aligned.four.wide.column
       [:a.ui.link {:href "/"}
        [:h1 "Systematic Review"]]]
      (when (contains? @state :identity)
        [:div.ui.right.floated.left.aligned.twelve.wide.column
         [menu-component]])]
     [:div.middle.aligned.row
      [:div.sixteen.wide.column
       [current-page-content]]]]]
   [notifier (notify-head) 2000]])
