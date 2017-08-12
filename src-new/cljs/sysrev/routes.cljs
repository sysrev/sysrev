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

(defn- go-project-panel [uri]
  (when (not= @(subscribe [:active-panel])
              [:project :project :overview])
    (dispatch [:reload [:project]]))
  (dispatch [:set-active-panel [:project :project :overview]
             uri]))

(sr-defroute
 home "/" []
 (go-project-panel "/"))

(sr-defroute
 project "/project" []
 (go-project-panel "/project"))

(sr-defroute
 articles "/project/articles" []
 (dispatch [:set-active-panel [:project :project :articles]
            "/project/articles"])
 (dispatch [:public-labels/hide-article])
 (dispatch [:reload [:project/public-labels]]))

(sr-defroute
 articles-id "/project/articles/:article-id" [article-id]
 (let [article-id (js/parseInt article-id)]
   (dispatch [:require [:article article-id]])
   (dispatch [:reload [:article article-id]])
   (dispatch [:set-active-panel [:project :project :articles]
              (str "/project/articles/" article-id)])
   (dispatch [:public-labels/show-article article-id])))

(sr-defroute
 project-user "/project/user" []
 (dispatch [:set-active-panel [:project :user :labels]
            "/project/user"])
 (dispatch [:user-labels/hide-article])
 (when-let [user-id @(subscribe [:self/user-id])]
   (dispatch [:reload [:member/articles user-id]])))

(sr-defroute
 project-user-article "/project/user/article/:article-id" [article-id]
 (let [article-id (js/parseInt article-id)]
   (dispatch [:require [:article article-id]])
   (dispatch [:reload [:article article-id]])
   (dispatch [:set-active-panel [:project :user :labels]
              (str "/project/user/article/" article-id)])
   (dispatch [:user-labels/show-article article-id])))

(sr-defroute
 project-labels "/project/labels" []
 (dispatch [:set-active-panel [:project :project :labels]
            "/project/labels"]))

(sr-defroute
 review "/project/review" []
 (dispatch [:require [:review/task]])
 (dispatch [:set-active-panel [:project :review]
            "/project/review"])
 (when-let [task-id @(subscribe [:review/task-id])]
   (dispatch [:reload [:article task-id]])))

(sr-defroute
 project-settings "/project/settings" []
 (dispatch [:reload [:project/settings]])
 (dispatch [:set-active-panel [:project :project :settings]
            "/project/settings"]))

(sr-defroute
 invite-link "/project/invite-link" []
 (dispatch [:set-active-panel [:project :project :invite-link]
            "/project/invite-link"]))

(sr-defroute
 login "/login" []
 (dispatch [:set-active-panel [:login]
            "/login"]))

(sr-defroute
 select-project "/select-project" []
 (dispatch [:set-active-panel [:select-project]
            "/select-project"]))

(sr-defroute
 user-settings "/user/settings" []
 (dispatch [:set-active-panel [:user-settings]
            "/user/settings"]))

(defn set-active-subpanel [db prefix uri]
  (assoc-in db [:state :navigation :subpanels (vec prefix)] uri))

(defn- load-default-panels [db]
  (->> [[[]
         "/"]

        [[:project]
         "/project"]

        [[:project :project]
         "/project"]

        [[:project :project :overview]
         "/project"]

        [[:project :project :articles]
         "/project/articles"]

        [[:project :user]
         "/project/user"]

        [[:project :user :labels]
         "/project/user"]

        [[:project :project :labels]
         "/project/labels"]

        [[:project :review]
         "/project/review"]

        [[:project :project :settings]
         "/project/settings"]

        [[:project :project :invite-link]
         "/project/invite-link"]

        [[:login]
         "/login"]

        [[:select-project]
         "/select-project"]

        [[:user-settings]
         "/user/settings"]]
       (reduce (fn [db [prefix uri]]
                 (set-active-subpanel db prefix uri))
               db)))

(reg-event-db :ui/load-default-panels load-default-panels)

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
