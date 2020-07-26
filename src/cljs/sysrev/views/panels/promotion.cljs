(ns sysrev.views.panels.promotion
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [sysrev.views.components.core :refer
             [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.macros :refer-macros [sr-defroute setup-panel-state]]
            ))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:promotion])

(defn teampro-promotion []
  [:div.ui.aligned.segment {:style {:font-size "large" :line-height "18pt" :width "800px"}}
   [:div {:style {:width "700px"}}
    [:h1 "Sysrev Team Pro - $500 for Reviews"]
    [:p
     "Sysrev will award 10 selected projects $500 for reviewer payments. If selected, a project of your choice
     will receive $500 which can be used to pay reviewers to complete review tasks. Apply at the bottom of the page before August 31."]
    [:p "Learn more about sysrev document reviews at" [:a {:href "/"} " sysrev.com "]
     "or the " [:a {:href "https://blog.sysrev.com/what-is-sysrev"} "what is sysrev?"] " blog."]

    [:h2 "What happens after I apply?"]
    [:p "We will email acceptance decisions within a week. If your project is accepted, we will:"
      [:ul
       [:li "Ask you to sign up for team pro."]
       [:li "Enable the sysrev reviewer payments feature on your account."]
       [:li "Provide $500 to your selected project."]
       ]
      "This review budget expires in August 2021 and individual payments cannot exceed $2. Reviewer payments are subject to
      the standard 20% overhead rate."]

   [:h2 "Can I apply?"]
   [:p "Awarded applications are required to either sign up for Team Pro at $30/month or already have a team pro account.
    Priority is given to new team pro accounts, but anybody can apply! "]
    [:h2 "Apply Below"]
    [:iframe {
              :src "https://docs.google.com/forms/d/e/1FAIpQLSfwwGo-rbzxZAurT3CmW0pdpcw82a3kYSQbFpsJ41-o5zpJDw/viewform?embedded=true"
              :width "640" :height "1100" :frameBorder "0" :marginHeight "0" :marginWidth "0"} "Loading…"]
    ]])

(defn promotion-expired []
  [:div.ui.aligned.segment {:style {:text-align "center"}}
   [:div {:style {:text-align "center"}}
    [:h1 "This Promotion has expired"]]])

(defmethod panel-content [:promotion] []
  (fn [_child]
    (if (< (cljs-time.core/now) (cljs-time.core/date-time 2020 9 1))
      [teampro-promotion]
      [promotion-expired])))

(sr-defroute promotion "/promotion" []
             (dispatch [:set-active-panel panel]))