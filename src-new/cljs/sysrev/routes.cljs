(ns sysrev.routes
  (:require
   [pushy.core :as pushy]
   [secretary.core :as secretary]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
   [sysrev.util :refer [scroll-top]]
   [sysrev.base :refer [history]]
   [sysrev.events.notes :refer [sync-article-notes]])
  (:require-macros [secretary.core :refer [defroute]]
                   [sysrev.macros :refer [sr-defroute]]))

(defn- go-project-panel []
  (dispatch [:reload [:project]])
  (dispatch [:set-active-panel [:project :project :overview]]))

(sr-defroute
 home "/" []
 (go-project-panel))

(sr-defroute
 project "/project" []
 (go-project-panel))

(sr-defroute
 articles "/project/articles" []
 (dispatch [:set-active-panel [:project :project :articles]])
 (dispatch [:article-list/hide-article])
 (dispatch [:reload [:project/public-labels]]))

(sr-defroute
 articles-id "/project/articles/:article-id" [article-id]
 (let [article-id (js/parseInt article-id)]
   (dispatch [:require [:article article-id]])
   (dispatch [:reload [:article article-id]])
   (dispatch [:set-active-panel [:project :project :articles]])
   (dispatch [:article-list/show-article article-id])))

(sr-defroute
 project-user "/project/user" []
 (dispatch [:set-active-panel [:project :user :labels]])
 (dispatch [:user-labels/hide-article])
 (when-let [user-id @(subscribe [:self/user-id])]
   (dispatch [:reload [:member/articles user-id]])))

(sr-defroute
 project-user-article "/project/user/article/:article-id" [article-id]
 (let [article-id (js/parseInt article-id)]
   (dispatch [:require [:article article-id]])
   (dispatch [:reload [:article article-id]])
   (dispatch [:set-active-panel [:project :user :labels]])
   (dispatch [:user-labels/show-article article-id])))

(sr-defroute
 project-labels "/project/labels" []
 (dispatch [:set-active-panel [:project :project :labels]]))

(sr-defroute
 review "/project/review" []
 (dispatch [:require [:review/task]])
 (dispatch [:set-active-panel [:project :review]])
 (when-let [task-id @(subscribe [:review/task-id])]
   (dispatch [:reload [:article task-id]])))

(sr-defroute
 project-settings "/project/settings" []
 (dispatch [:reload [:project/settings]])
 (dispatch [:set-active-panel [:project :project :settings]]))

(sr-defroute
 invite-link "/project/invite-link" []
 (dispatch [:set-active-panel [:project :project :invite-link]]))

(sr-defroute
 login "/login" []
 (dispatch [:set-active-panel [:login]]))

(sr-defroute
 select-project "/select-project" []
 (dispatch [:set-active-panel [:select-project]]))

(sr-defroute
 user-settings "/user/settings" []
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
