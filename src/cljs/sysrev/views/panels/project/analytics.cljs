(ns sysrev.views.panels.project.analytics
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.stripe :as stripe]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics])

(defn admin? []
  (or @(subscribe [:member/admin?])
      @(subscribe [:user/admin?])))

(defn not-admin-description []
  [:div.ui.aligned.segment {:id "no-admin-paywall" :style {:text-align "center" }}
   [:div
    [:h2 "Analytics is not available for your account"]
    [:h3 "1. You must have admin permissions for this project" [:br] "2. Project owner must have a Pro account" [:br]
     "see " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [:span "Learn more about analytics in the below video or at "
     [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics"]]
    [:br]
    [:span "or try the "
     [:a {:href "/p/21696/analytics/concordance"} "Live Demo"]]
    [:br][:br]]
   [:div {:style {:height "50vh"}}
    [:iframe {:width "100%" :height "100%" :src "https://www.youtube.com/embed/FgxJ4zTVUn4"
              :frameBorder "0" :allow "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"
              :allowFullScreen "{true}"}]]])

(defn paywall []
  [:div.ui.aligned.segment {:id "paywall" :style {:text-align "center" }}
   [:div
    [:h2 "Analytics is only available for pro accounts" [:br]
     "Sign up at " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [:span "Learn more at "
     [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics"]]
    [:br]
    [:span "or try the  "
     [:a {:href "/p/21696/analytics/concordance"} "Live Demo"]]
    [:br][:br]]
   [:div  {:style {:height "50vh"}}
    [:iframe {:width "100%" :height "100%" :src "https://www.youtube.com/embed/FgxJ4zTVUn4"
              :frameBorder "0" :allow "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"
              :allowFullScreen true}]]])

(defn demo-message []
  [:div {:style {:text-align "right"}}
   [:span {:style {:color "red"}}
    [:b "Analytics Demo - Register For " [:a {:href "/pricing"} "Sysrev Pro"] " To Access On Personal Projects"]]])
(defmethod panel-content [:project :project :analytics] []
  (fn [child]
    (let [project-id    @(subscribe [:active-project-id])
          project-plan  @(subscribe [:project/plan project-id])
          superuser?    @(subscribe [:user/actual-admin?])]
      [:div.project-content
       (cond
         superuser? child ;superusers like the insilica team can always see analytics
         (= project-id 21696)                             [:div [demo-message] child]
         (not (admin?))                                   [not-admin-description]
         (and (contains? stripe/pro-plans project-plan) (admin?)) child ;project admins of paid plan projects can see analytics
         :else                                            [paywall])])))
