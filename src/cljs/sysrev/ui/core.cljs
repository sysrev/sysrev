(ns sysrev.ui.core
  (:require
   [sysrev.base :refer [state]]
   [sysrev.util :refer [full-size?]]
   [sysrev.state.core :refer
    [current-page on-page? logged-in? active-project-id]]
   [sysrev.routes :refer [data-initialized? on-public-page?]]
   [sysrev.notify :refer [active-notification]]
   [sysrev.ui.components :refer [loading-screen notifier]]
   [sysrev.ui.sysrev :refer
    [project-page project-overview-box project-predict-report-box]]
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
        (on-page? :project)
        [project-page (-> @state :page :project :tab)
         (case (-> @state :page :project :tab)
           :overview [project-overview-box]
           :predict [project-predict-report-box]
           nil)]
        (on-page? :labels) [project-page :labels [labels-page]]
        (on-page? :user-profile)
        [project-page :user-profile [user-profile-page]]
        (on-page? :classify)
        [project-page :classify [classify-page]]
        (on-page? :article)
        (let [project-id (active-project-id)
              article-id (-> @state :page :article :id)
              article-project-id
              (and article-id (d/data [:articles article-id :project-id]))]
          (if (and project-id article-project-id
                   (= project-id article-project-id))
            [project-page :article [article-page]]
            [article-page]))
        (on-page? :select-project) [select-project-page]
        (on-page? :login) [login-register-page {:register? false}]
        (on-page? :register) [login-register-page {:register? true}]
        (on-page? :request-password-reset) [request-password-reset-page]
        (on-page? :reset-password) [password-reset-page]
        true [:div [:h1 "Route not found"]]))

(defn header-menu-full []
  (let [{:keys [user-id email name]} (-> @state :identity)
        display-name (or name email)]
    [:div.ui.top.menu.site-menu
     [:div.ui.container
      [:a.header.item
       {:href "/"}
       [:h3.ui.blue.header
        "sysrev.us"]]
      (if (logged-in?)
        [:div.right.menu
         [:a.item {:href "/project"} "Project"]
         [:a.item.blue-text {:href (str "/user/" user-id)}
          [:div
           [:i.blue.user.icon]
           display-name]]
         (when (d/admin-user? user-id)
           [:a.item {:href "/select-project"}
            "Change project"])
         [:a.item.distinct.middle.aligned {:on-click ajax/do-post-logout}
          "Log out"
          #_ [:i.blue.sign.out.fitted.icon
              {:style {:padding-left "4px"
                       :margin-bottom "-3px"}}]]
         [:a.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:href "/login"}
          "Log in"]
         [:a.item.distinct {:href "/register"}
          "Register"]])]]))

(defn header-menu-mobile []
  (if (logged-in?)
    (let [{:keys [user-id email name]} (-> @state :identity)
          display-name (or name email)]
      [:div.ui.menu.site-menu
       [:a.item
        {:href "/"}
        [:h3.ui.blue.header
         "sysrev.us"]]
       [:a.item {:href "/project/classify"} "Classify"]
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
             (when (d/admin-user? user-id)
               [:a.item {:href "/select-project"} "Change project"])]]])]
       [:div.right.menu
        [:a.item {:href "/project"} "Project"]
        [:a.item.blue-text {:href (str "/user/" user-id)}
         [:i.large.blue.user.icon {:style {:margin "0px"}}]]
        [:div.item
         [:a.ui.button {:on-click ajax/do-post-logout}
          "Log out"]]
        [:a.item {:style {:width "0" :padding "0"}}]]])
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
  [:div.main-content
   (when (contains? @state :identity)
     (if (full-size?)
       [header-menu-full]
       [header-menu-mobile]))
   [:div.ui.container.page-content
    [current-page-content]]
   [notifier (active-notification)]])
