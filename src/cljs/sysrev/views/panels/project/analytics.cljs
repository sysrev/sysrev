(ns sysrev.views.panels.project.analytics
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [sysrev.views.charts :as charts]
            [sysrev.views.components.core :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics])

(defn admin? []
  (or @(subscribe [:member/admin?])
      @(subscribe [:user/admin?])))


(defn not-admin-description []
  [:div.ui.aligned.segment {:style {:text-align "center" }}
   [:div
    [:h2 "Analytics is not available for your account"]
    [:h3 "1. You must have admin permissions for this project" [:br] "2. Project owner must have a Pro account" [:br]
     "see " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [:span "Learn more about analytics in the below video or at "
     [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics"]]
    [:br]
    [:span "Play with a live demo of analytics at "
     [:a {:href "p/21696/analytics/concordance"} "Live Demo"]]
    [:br][:br]]
   [:div {:style {:height "50vh"}}
    [:iframe {:width "100%" :height "100%" :src "https://www.youtube.com/embed/HmQhiVNtB2s"
              :frameborder "0" :allow "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"
              :allowfullscreen "true"}]]]
  )

(defn paywall []
  [:div.ui.aligned.segment {:style {:text-align "center" }}
   [:div
    [:h2 "Analytics is only available for pro accounts" [:br]
     "Sign up at " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [:span "Learn more about analytics in the below video or at "
     [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics"]]
    [:br]
    [:span "Play with a live demo of analytics at "
     [:a {:href "p/21696/analytics/concordance"} "Live Demo"]]
    [:br][:br]]
   [:div  {:style {:height "50vh"}}
    [:iframe {:width "100%" :height "100%" :src "https://www.youtube.com/embed/HmQhiVNtB2s"
              :frameborder "0" :allow "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"
              :allowfullscreen "true"}]]]
  )

(defmethod panel-content [:project :project :analytics] []
  (fn [child]
    (let [project-id    @(subscribe [:active-project-id])
          project-plan  @(subscribe [:project/plan project-id])
          superuser?    @(subscribe [:user/actual-admin?])]
      [:div.project-content
       (cond
         superuser? child ;superusers like the insilica team can always see analytics
         (= project-id 21696)
         [:div
          [:div {:style {:text-align "right"}}
           [:span {:style {:color "red"}}
            [:b "Analytics Demo - Register For " [:a {:href "/pricing"} "Sysrev Pro"] " To Access On Personal Projects"]]
           ]
          child]
         (not (admin?)) [not-admin-description]
         (and (not= project-plan "Basic") (admin?)) child ;project admins of paid plan projects can see analytics
         :else [paywall])
       ])))
