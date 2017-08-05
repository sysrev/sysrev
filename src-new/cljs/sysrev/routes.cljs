(ns sysrev.routes
  (:require
   [pushy.core :as pushy]
   [secretary.core :as secretary]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
   [sysrev.util :refer [scroll-top]]
   [sysrev.base :refer [history]])
  (:require-macros [secretary.core :refer [defroute]]))

(defn before-route-change []
  (when-let [article-id @(subscribe [:review/editing-id])]
    ;; Send changes to unconfirmed labels before leaving page
    (dispatch-sync [:review/send-active-labels
                    false {:article-id article-id}])))

(defn- go-project-panel []
  (dispatch [:reload [:project]])
  (dispatch [:set-active-panel [:project :project :overview]]))

(defroute home "/" []
  (before-route-change)
  (go-project-panel))

(defroute project "/project" []
  (before-route-change)
  (go-project-panel))

(defroute articles "/project/articles" []
  (before-route-change)
  (dispatch [:set-active-panel [:project :project :articles]])
  (dispatch [:article-list/hide-article])
  (when-let [label-id @(subscribe [:article-list/label-id])]
    (dispatch [:reload [:project/label-activity label-id]])))

(defroute articles-id "/project/articles/:article-id" [article-id]
  (before-route-change)
  (let [article-id (js/parseInt article-id)]
    (dispatch [:require [:article article-id]])
    (dispatch [:reload [:article article-id]])
    (dispatch [:set-active-panel [:project :project :articles]])
    (dispatch [:article-list/show-article article-id])))

(defroute member-account "/project/user" []
  (before-route-change)
  (dispatch [:set-active-panel [:project :member-account]])
  (when-let [user-id @(subscribe [:self/user-id])]
    (dispatch [:reload [:member/articles user-id]])))

(defroute project-labels "/project/labels" []
  (before-route-change)
  (dispatch [:set-active-panel [:project :project :labels]]))

(defroute review "/project/review" []
  (before-route-change)
  (dispatch [:set-active-panel [:project :review]]))

(defroute project-settings "/project/settings" []
  (before-route-change)
  (dispatch [:set-active-panel [:project :project :settings]]))

(defroute invite-link "/project/invite-link" []
  (before-route-change)
  (dispatch [:set-active-panel [:project :project :invite-link]]))

(defroute login "/login" []
  (before-route-change)
  (dispatch [:set-active-panel [:login]]))

(defroute select-project "/select-project" []
  (before-route-change)
  (dispatch [:set-active-panel [:select-project]]))

(defroute user-settings "/user/settings" []
  (before-route-change)
  (dispatch [:set-active-panel [:user-settings]]))

(defn force-dispatch [uri]
  (secretary/dispatch! uri))

(defn nav
  "Change the current route."
  [route]
  (pushy/set-token! history route))

(defn nav-scroll-top
  "Change the current route then scroll to top of page."
  [route]
  (pushy/set-token! history route)
  (scroll-top))
