(ns sysrev.ui.core
  (:require
   [sysrev.base :refer
    [st st-if-exists display-ready loading-state display-state]]
   [sysrev.util :refer [full-size?]]
   [sysrev.state.core :as st :refer
    [data current-page logged-in? current-project-id]]
   [sysrev.routes :refer [data-initialized?]]
   [sysrev.notify :refer [active-notification]]
   [sysrev.ui.components :refer [notifier]]
   [sysrev.shared.components :refer [loading-content]]
   [sysrev.ui.sysrev :refer
    [project-page project-overview-box project-predict-report-box
     project-settings-page]]
   [sysrev.ui.labels :refer [labels-page]]
   [sysrev.ui.login :refer [login-register-page join-project-page]]
   [sysrev.ui.user-profile :refer [user-profile-page]]
   [sysrev.ajax :as ajax]
   [sysrev.ui.classify :refer [classify-page]]
   [sysrev.ui.article-page :refer [article-page]]
   [sysrev.ui.select-project :refer [select-project-page]]
   [sysrev.ui.password-reset :refer
    [password-reset-page request-password-reset-page]]
   [sysrev.ui.article-list :refer [articles-page]]
   [sysrev.ui.dev-tools :refer [site-dev-tools-component]]
   [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [with-mount-hook]]))

(defn loading-indicator []
  (when (and (not @display-ready)
             (empty? @loading-state))
    [:div.ui.small.active.inline.loader]))


(defmulti logged-out-content current-page)
(defmethod logged-out-content :login []
  [login-register-page])
(defmethod logged-out-content :register []
  [login-register-page])
(defmethod logged-out-content :request-password-reset []
  [request-password-reset-page])
(defmethod logged-out-content :reset-password []
  [password-reset-page])
(defmethod logged-out-content :default []
  [login-register-page])


(defmulti logged-in-content current-page)
(defmethod logged-in-content :project []
  [project-page (st :page :project :tab)
   (case (st :page :project :tab)
     :overview [project-overview-box]
     :predict [project-predict-report-box]
     nil)])

(defmethod logged-in-content :register []
  [join-project-page])
(defmethod logged-in-content :labels []
  [project-page :labels [labels-page]])
(defmethod logged-in-content :project-settings []
  [project-page :settings [project-settings-page]])
(defmethod logged-in-content :user-profile []
  [project-page :user-profile [user-profile-page]])
(defmethod logged-in-content :article []
  (let [project-id (current-project-id)
        article-id (st :page :article :id)
        article-project-id
        (and article-id (data [:articles article-id :project-id]))]
    (if (and project-id article-project-id
             (= project-id article-project-id))
      [project-page :article [article-page]]
      [article-page])))

(defmethod logged-in-content :classify []
  [project-page :classify [classify-page]])
(defmethod logged-in-content :select-project []
  [select-project-page])
(defmethod logged-in-content :articles []
  [project-page :articles [articles-page]])

(defmethod logged-in-content :default []
  [:div [:h1 "Route not found"]])



(defn current-page-content []
  (if-not (logged-in?)
    [:div.ui.container
     [logged-out-content]]
    (if (nil? (current-project-id))
      [select-project-page]
      [logged-in-content])))


(defn header-menu-full []
  (let [{:keys [user-id email name]} (st :identity)
        display-name (or name email)]
    [:div.ui.top.menu.site-menu
     [:div.ui.container
      [:a.header.item
       {:href "/"}
       [:h3.ui.blue.header
        "sysrev.us"]]
      [:a.item.loading-indicator [loading-indicator]]
      (if (logged-in?)
        [:div.right.menu
         (when (st/admin-user? user-id)
           [:a.item
            {:on-click #(ajax/post-clear-query-cache)}
            "Clear query cache"])
         [:a.item.blue-text {:href (str "/user/" user-id)}
          [:div
           [:i.blue.user.icon]
           display-name]]
         (when (or (st/admin-user? user-id)
                   (< 1 (count (st :identity :projects))))
           [:a.item {:href "/select-project"}
            "Change project"])
         [:a.item.distinct.middle.aligned {:on-click ajax/post-logout}
          "Log out"]
         [:a.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:href "/login"}
          "Log in"]
         #_ [:a.item.distinct {:href "/register"}
             "Register"]])]]))

(defn header-menu-mobile []
  (let [{:keys [user-id email name]} (st :identity)
        display-name (or name email)]
    [:div.ui.top.menu.site-menu
     [:div.ui.container
      [:a.header.item
       {:href "/"}
       [:h3.ui.blue.header
        "sysrev.us"]]
      [:a.item.loading-indicator [loading-indicator]]
      (if (logged-in?)
        [:div.right.menu
         #_
         (when (st/admin-user? user-id)
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
                 (when (st/admin-user? user-id)
                   [:a.item {:href "/select-project"} "Change project"])]]])])
         [:a.item.blue-text {:href (str "/user/" user-id)}
          [:i.large.blue.fitted.user.icon {:style {:margin "0px"}}]]
         (when (or (st/admin-user? user-id)
                   (< 1 (count (st :identity :projects))))
           [:a.item {:href "/select-project"}
            "Change project"])
         [:a.item.distinct.middle.aligned {:on-click ajax/post-logout}
          "Log out"]
         [:a.item {:style {:width "0" :padding "0"}}]]
        [:div.right.menu
         [:a.item.distinct {:href "/login"}
          "Log in"]])]]))

(defn main-content []
  (binding [sysrev.base/read-from-work-state false]
    (if (or (empty? (st :data))
            (not (data-initialized? (current-page) @display-state)))
      loading-content
      [:div.main-content
       (when (not= :not-found
                   (st-if-exists [:identity] :not-found))
         (if (full-size?)
           [header-menu-full]
           [header-menu-mobile]))
       [:div.ui
        [current-page-content]
        #_ [site-dev-tools-component]]
       [notifier (active-notification)]])))
