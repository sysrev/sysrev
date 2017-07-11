(ns sysrev.routes
  (:require
   [pushy.core :as pushy]
   [secretary.core :as secretary]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
   [sysrev.util :refer [scroll-top]]
   [sysrev.base :refer [history]]
   [sysrev.views.panels.project.article-list :as article-list])
  (:require-macros [secretary.core :refer [defroute]]))

(defn- go-project-panel []
  (dispatch [:reload [:project]])
  (dispatch [:set-active-panel [:project :project :overview]]))

(defroute home "/" []
  (go-project-panel))

(defroute project "/project" []
  (go-project-panel))

(defroute article-list "/project/articles" []
  (dispatch [:set-active-panel [:project :project :articles]])
  (when-let [label-id @(subscribe [::article-list/label-id])]
    (dispatch [:reload [:project/label-activity label-id]])))

(defroute member-account "/project/user" []
  (dispatch [:set-active-panel [:project :member-account]]))

(defroute project-labels "/project/labels" []
  (dispatch [:set-active-panel [:project :project :labels]]))

(defroute review "/project/review" []
  (dispatch [:set-active-panel [:project :review]]))

(defroute project-settings "/project/settings" []
  (dispatch [:set-active-panel [:project :project :settings]]))

(defroute invite-link "/project/invite-link" []
  (dispatch [:set-active-panel [:project :project :invite-link]]))

(defroute login "/login" []
  (dispatch [:set-active-panel [:login]]))

(defroute select-project "/select-project" []
  (dispatch [:set-active-panel [:select-project]]))

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
