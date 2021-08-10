(ns sysrev.views.panels.project.analytics
  (:require [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.core :refer [reload]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.shared.text :as shared]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics])

(defn- AnalyticsLearnMore [intro-text]
  [:p intro-text
   [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics"]
   [:br] "or try the "
   [:a {:href "/p/21696/analytics/concordance"} "Live Demo"]])

(defn- AnalyticsVideoEmbed []
  [:div {:style {:height "50vh" :margin-top "1em"}}
   [:iframe {:width "100%" :height "100%"
             :frame-border 0 :allow-full-screen true
             :src (shared/links :analytics-embed)
             :allow "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"}]])

(defn- NotAdminDescription []
  [:div.ui.center.aligned.segment {:id "no-admin-paywall"}
   [:div
    [:h2 "Analytics is not available for your account"]
    [:h3
     "1. You must have admin permissions for this project" [:br]
     "2. Project owner must have a Pro account" [:br]
     "see " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [AnalyticsLearnMore "Learn more about analytics in the video below or at "]]
   [AnalyticsVideoEmbed]])

(defn- Paywall []
  [:div.ui.center.aligned.segment {:id "paywall"}
   [:div
    [:h2 "Analytics is only available for pro accounts" [:br]
     "Sign up at " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [AnalyticsLearnMore "Learn more at "]]
   [AnalyticsVideoEmbed]])

(defn- DemoMessage []
  [:div {:style {:text-align "right"}}
   [:span {:style {:color "red"}}
    [:b "Analytics Demo - Register For "
     [:a {:href "/pricing"} "Sysrev Pro"]
     " To Access On Personal Projects"]]])

(defn- Panel [child]
  (let [project-id    @(subscribe [:active-project-id])
        project-plan  @(subscribe [:project/plan project-id])
        superuser?    @(subscribe [:user/dev?])
        admin?        @(subscribe [:member/admin?])]
    [:div.project-content
     (cond
       ;; superusers like the insilica team can always see analytics
       superuser?             child
       (= project-id 21696)   [:div [DemoMessage] child]
       (= project-id 40169)   [:div [DemoMessage] child]
       (not admin?)           [NotAdminDescription]
       ;; project admins of paid plan projects can see analytics
       (and admin? (plans-info/pro? project-plan))  child
       :else                  [Paywall])]))

(def-panel :project? true :panel panel
  :uri "/analytics" :params [project-id] :name analytics
  :on-route (do (reload :project project-id)
                (dispatch [:set-active-panel panel]))
  :content (fn [child] [Panel child]))
