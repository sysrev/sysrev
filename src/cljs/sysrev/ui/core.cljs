(ns sysrev.ui.core
  (:require
   [sysrev.base :refer [state]]
   [sysrev.util :refer [full-size?]]
   [sysrev.state.core :refer
    [current-page on-page? logged-in? active-project-id]]
   [sysrev.routes :refer [data-initialized? on-public-page?]]
   [sysrev.notify :refer [active-notification]]
   [sysrev.ui.components :refer [loading-screen notifier project-wrapper-div]]
   [sysrev.ui.sysrev :refer [project-page]]
   [sysrev.ui.labels :refer [labels-page]]
   [sysrev.ui.login :refer [login-register-page]]
   [sysrev.ui.user-profile :refer [user-profile-page]]
   [sysrev.ajax :as ajax]
   [sysrev.ui.classify :refer [classify-page]]
   [sysrev.ui.article-page :refer [article-page]]
   [sysrev.ui.select-project :refer [select-project-page]]
   [sysrev.ui.password-reset :refer
    [password-reset-page request-password-reset-page]]
   [reagent.core :as r]
   [sysrev.state.data :as d])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn logged-out-content []
  [:div.ui.segments
   [:div.ui.center.aligned.header.segment
    [:h2 "Please log in or register to access a project"]]])

(defn current-page-content []
  (cond (nil? (current-page)) [:div [:h1 "Route not found"]]
        (not (data-initialized? (current-page))) [loading-screen]
        (and (not (logged-in?))
             (not (on-public-page?))) [logged-out-content]
        (and (logged-in?)
             (nil? (active-project-id))) [select-project-page]
        (on-page? :project) [project-wrapper-div [project-page]]
        (on-page? :select-project) [select-project-page]
        (on-page? :labels) [project-wrapper-div [labels-page]]
        (on-page? :login) [login-register-page {:register? false}]
        (on-page? :register) [login-register-page {:register? true}]
        (on-page? :request-password-reset) [request-password-reset-page]
        (on-page? :reset-password) [password-reset-page]
        (on-page? :user-profile) [project-wrapper-div [user-profile-page]]
        (on-page? :classify) [project-wrapper-div [classify-page]]
        (on-page? :article) [project-wrapper-div [article-page]]
        true [:div [:h1 "Route not found"]]))

(defn header-menu-full []
  [:div.ui.menu
   [:a.item
    {:href "/"}
    [:h2.ui.blue.header
     "sysrev.us"]]
   (if (logged-in?)
     (let [{:keys [user-id email name]} (-> @state :identity)
           display-name (or name email)]
       [:div.right.menu
        [:a.item.blue-text {:href (str "/user/" user-id)}
         [:div
          [:i.blue.user.icon]
          display-name]]
        [:a.item {:href "/project"} "Project"]
        [:a.item {:href "/labels"} "Labels"]
        [:a.item {:href "/classify"} "Classify"]
        (when (d/admin-user? user-id)
          [:div.item
           [:a.ui.button {:href "/select-project"}
            "Change project"]])
        [:div.item
         [:a.ui.button {:on-click ajax/do-post-logout}
          "Log out"]]])
     [:div.right.menu
      #_ [:a.item {:href "/project"} "Project"]
      #_ [:a.item {:href "/labels"} "Labels"]
      [:div.item
       [:a.ui.button {:href "/login"}
        "Log in"]]
      [:div.item
       [:a.ui.button {:href "/register"}
        "Register"]]])])

(defn header-menu-mobile []
  (if (logged-in?)
    (let [{:keys [user-id email name]} (-> @state :identity)
          display-name (or name email)]
      [:div.ui.menu
       [:a.item
        {:href "/"}
        [:h3.ui.blue.header
         "sysrev.us"]]
       [:a.item {:href "/classify"} "Classify"]
       [:div.item
        (let [dropdown
              (with-mount-hook
                #(.dropdown (js/$ (r/dom-node %))))]
          [dropdown
           [:div.ui.dropdown
            [:input {:type "hidden" :name "menu-dropdown"}]
            [:i.chevron.down.icon
             {:style {:margin "0px"}}]
            [:div.menu
             [:a.item {:href "/project"} "Project"]
             [:a.item {:href "/labels"} "Labels"]
             (when (d/admin-user? user-id)
               [:a.item {:href "/select-project"} "Change project"])]]])]
       [:div.right.menu
        [:a.item.blue-text {:href (str "/user/" user-id)}
         [:i.large.blue.user.icon {:style {:margin "0px"}}]]
        [:div.item
         [:a.ui.button {:on-click ajax/do-post-logout}
          "Log out"]]]])
    [:div.ui.menu
     [:a.item
      {:href "/"}
      [:h3.ui.blue.header
       "sysrev.us"]]
     [:div.right.menu
      [:div.item
       [:a.ui.button {:href "/login"}
        "Log in"]]
      [:div.item
       [:a.ui.button {:href "/register"}
        "Register"]]]]))

(defn main-content []
  [:div
   [:div.ui.container.main-content
    [:div.ui.grid
     [:div.middle.aligned.row
      [:div.ui.sixteen.wide.computer.only.column
       (when (contains? @state :identity)
         [header-menu-full])]
      [:div.ui.sixteen.wide.mobile.only.tablet.only.column
       (when (contains? @state :identity)
         [header-menu-mobile])]]
     [:div.middle.aligned.row
      [:div.sixteen.wide.column
       [current-page-content]]]]]
   [notifier (active-notification)]])
