(ns sysrev.views.panels.promotion
  (:require [cljs-time.core :as time]
            [re-frame.core :refer [dispatch]]
            [sysrev.macros :refer-macros [def-panel setup-panel-state]]
            [sysrev.base :as base]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:promotion])

(defn- TeamProPromotion []
  [:div.ui.aligned.segment {:style {:font-size "large" :line-height "18pt" :width "800px"}}
   [:div {:style {:width "700px"}}
    [:h1 "Sysrev Team Pro - $500 for Reviews"]
    [:p
     "Sysrev will award 10 selected projects $500 for reviewer payments. If selected, a project of your choice
     will receive $500 which can be used to pay reviewers to complete review tasks. Apply at the bottom of the page before August 31."]
    [:p "Learn more about sysrev document reviews at"
     [:a {:href "/"} " sysrev.com"]
     (if @base/show-blog-links
       [:span " or the "
        [:a {:href "https://blog.sysrev.com/what-is-sysrev"} "what is sysrev?"] " blog."]
       [:span "."])]
    [:h2 "What happens after I apply?"]
    [:p "We will email acceptance decisions within a week. If your project is accepted, we will:"
     [:ul
      [:li "Ask you to sign up for team pro."]
      [:li "Enable the sysrev reviewer payments feature on your account."]
      [:li "Provide $500 to your selected project."]]
     "This review budget expires in August 2021 and per article payments cannot exceed $2. Reviewer payments are subject to the standard 20% overhead rate."]
    [:h2 "Can I apply?"]
    [:p "Awarded applications are required to either sign up for Premium at $10/month or already have a team pro account."]
    [:p "Priority is given to new team pro accounts, but anybody can apply!"]
    [:h2 "Apply Below"]
    [:iframe {:src "https://docs.google.com/forms/d/e/1FAIpQLSfwwGo-rbzxZAurT3CmW0pdpcw82a3kYSQbFpsJ41-o5zpJDw/viewform?embedded=true"
              :width "640" :height "1100" :frameBorder "0" :marginHeight "0" :marginWidth "0"} "Loading…"]]])

(defn- PromotionExpired []
  [:div.ui.center.aligned.segment>h1.ui.center.aligned.header
   "This Promotion has expired"])

(defn- Panel []
  (if (< (time/now) (time/date-time 2020 9 1))
    [TeamProPromotion]
    [PromotionExpired]))

(def-panel :uri "/promotion" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content [Panel])
