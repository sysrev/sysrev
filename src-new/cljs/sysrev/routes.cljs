(ns sysrev.routes
  (:require
   [pushy.core :as pushy]
   [secretary.core :as secretary]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
   [sysrev.util :refer [scroll-top]]
   [sysrev.base :refer [history]]
   [sysrev.views.panels.article-list :as article-list])
  (:require-macros [secretary.core :refer [defroute]]))

(defn- go-project-overview []
  (dispatch [:reload [:project]])
  (dispatch [:set-active-panel :project-overview]))

(defroute home "/" []
  (go-project-overview))

(defroute project "/project" []
  (go-project-overview))

(defroute article-list "/project/articles" []
  (dispatch [:set-active-panel :articles])
  (when-let [label-id @(subscribe [::article-list/label-id])]
    (dispatch [:reload [:project/label-activity label-id]])))

(defroute user-profile "/user" []
  (dispatch [:set-active-panel :user-profile]))

(defroute user-profile-id "/user/:user-id" [user-id]
  (let [user-id (js/parseInt user-id)]
    (dispatch [:set-active-panel :user-profile])
    #_ (dispatch [:user-profile/set-user user-id])))

(defroute project-labels "/project/labels" []
  (dispatch [:set-active-panel :labels]))

(defroute classify "/project/classify" []
  (dispatch [:set-active-panel :classify]))

(defroute login "/login" []
  (dispatch [:set-active-panel :login]))

(defroute select-project "/select-project" []
  (dispatch [:set-active-panel :select-project]))

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
