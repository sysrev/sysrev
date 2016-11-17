(ns sysrev-web.ui.core
  (:require
   [sysrev-web.base :refer [state]]
   [sysrev-web.util :refer [full-size?]]
   [sysrev-web.state.core :refer
    [current-page on-page? logged-in? active-project-id]]
   [sysrev-web.routes :refer [data-initialized? on-public-page?]]
   [sysrev-web.notify :refer [active-notification]]
   [sysrev-web.ui.components :refer [loading-screen notifier project-wrapper-div]]
   [sysrev-web.ui.sysrev :refer [project-page]]
   [sysrev-web.ui.labels :refer [labels-page]]
   [sysrev-web.ui.login :refer [login-register-page]]
   [sysrev-web.ui.user-profile :refer [user-profile-page]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.ui.classify :refer [classify-page]]
   [sysrev-web.ui.article-page :refer [article-page]]
   [sysrev-web.ui.select-project :refer [select-project-page]]
   [reagent.core :as r]
   [sysrev-web.state.data :as d])
  (:require-macros [sysrev-web.macros :refer [with-mount-hook]]))

(defn logged-out-content []
  [:div.ui.segments
   [:div.ui.center.aligned.header.segment
    [:h2 "Please log in or register to access a project"]]])

#_
(defn logged-out-content []
  [:div.ui.segments
   [:div.ui.top.attached.center.aligned.header.segment
    [:h2 "Please log in or register to access a project"]]
   [:div.ui.bottom.attached.segment
    [:div
     {:style (if (full-size?)
               {:width "80%"
                :margin-left "auto"
                :margin-right "auto"}
               {})}
     (doall
      (->>
       (d/data :all-projects)
       (map (fn [[project-id project]]
              ^{:key {:project-list project-id}}
              [:div.ui.blue.segment
               [:h4 (:name project)]]))))]]])

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
        (on-page? :login) [login-register-page false]
        (on-page? :register) [login-register-page true]
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
        [:div.item
         [:a.ui.button {:href "/select-project"}
          "Change project"]]
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
                #(.dropdown (js/$ (r/dom-node %)))
                [:div.ui.dropdown
                 [:input {:type "hidden" :name "menu-dropdown"}]
                 [:i.chevron.down.icon
                  {:style {:margin "0px"}}]
                 [:div.menu
                  [:a.item {:href "/project"} "Project"]
                  [:a.item {:href "/labels"} "Labels"]]])]
          [dropdown])]
       [:div.right.menu
        [:a.item.blue-text {:href (str "/user/" user-id)}
         [:i.large.blue.user.icon {:style {:margin "0px"}}]]
        [:div.item
         [:a.ui.button {:href "/select-project"}
          "Change project"]]
        [:div.item
         [:a.ui.button {:on-click ajax/do-post-logout}
          "Log out"]]]])
    [:div.ui.menu
     [:a.item
      {:href "/"}
      [:h3.ui.blue.header
       "sysrev.us"]]
     [:div.right.menu
      #_
      [:div.item
       (let [dropdown
             (with-mount-hook
               #(.dropdown (js/$ (r/dom-node %)))
               [:div.ui.dropdown
                [:input {:type "hidden" :name "menu-dropdown"}]
                [:i.chevron.down.icon
                 {:style {:margin "0px"}}]
                [:div.menu
                 [:a.item {:href "/project"} "Project"]
                 [:a.item {:href "/labels"} "Labels"]]])]
         [dropdown])]
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
