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
     "Sysrev will award 10 selected document reviews or systematic reviews $500 for reviewer payments.
         This promotion will be awarded when teams create new " [:b "Team Pro "] "sysrev accounts.
         Apply at the bottom of the page. Applications close on August 31."]
    [:p "Learn more about sysrev document reviews at" [:a {:href "/"} " sysrev.com "]
     "or the " [:a {:href "https://blog.sysrev.com/what-is-sysrev"} "what is sysrev?"] " blog."]

    [:h2 "What happens after I apply?"]
    [:p "We will email acceptance decisions within a week.
    If accepted, you will sign up for Team Pro and Sysrev will add $500 to your project of choice."]

    [:h2 "Are there restrictions on how I can use the award?"]
     [:p "If your project has been accepted, we will:"
      [:ul
       [:li "Ask you to sign up for team pro."]
       [:li "Enable the sysrev reviewer payments feature on your account."]
       [:li "Provide $500 to your selected project."]
       ]
      "Review budget expires after 1 year and individual payments cannot exceed $2. Reviewer payments are subject to
      the standard 20% overhead rate."]

   [:h2 "Who is eligible for this award?"]
   [:p "Awarded applications will be required to sign up for
   Sysrev team pro at $30/month. This subscription cannot be paid for through the provided credit, which is only meant
   for reviewer payments. Anybody can apply!"]

    [:h2 "Apply Below"]
    [:iframe {
              :src "https://docs.google.com/forms/d/e/1FAIpQLSfwwGo-rbzxZAurT3CmW0pdpcw82a3kYSQbFpsJ41-o5zpJDw/viewform?embedded=true"
              :width "640" :height "1100" :frameBorder "0" :marginHeight "0" :marginWidth "0"} "Loading…"]
    ]
   [:div "hi"]])

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