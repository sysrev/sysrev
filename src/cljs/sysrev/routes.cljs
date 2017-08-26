(ns sysrev.routes
  (:require
   [pushy.core :as pushy]
   [secretary.core :as secretary]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
   [sysrev.util :refer [scroll-top ensure-dom-elt-visible-soon]]
   [sysrev.base :refer [history]]
   [sysrev.events.notes :refer [sync-article-notes]]
   [sysrev.events.ui :refer [set-subpanel-default-uri]]
   [sysrev.macros])
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
 (let [panel [:project :project :articles]
       item [:project/public-labels]
       set-panel [:set-active-panel [:project :project :articles]
                  "/project/articles"]
       ensure-visible #(ensure-dom-elt-visible-soon
                        ".article-list-view div.ui.segment.article-nav")
       on-article? (and (= @(subscribe [:active-panel]) panel)
                        @(subscribe [:public-labels/article-id]))
       data-loaded? @(subscribe [:have? item])]
   (if (and on-article? data-loaded?)
     (do (dispatch set-panel)
         (dispatch [:public-labels/hide-article])
         (ensure-visible))
     (do (dispatch
          [:data/after-load item :project-articles-route
           (list set-panel
                 [:public-labels/hide-article]
                 ensure-visible)])
         (dispatch [:require item])
         (dispatch [:reload item])))))

(sr-defroute
 articles-id "/project/articles/:article-id" [article-id]
 (let [article-id (js/parseInt article-id)
       item [:article article-id]]
   (dispatch
    [:data/after-load item :project-articles-route
     (list [:set-active-panel [:project :project :articles]
            (str "/project/articles/" article-id)]
           [:public-labels/show-article article-id]
           #(ensure-dom-elt-visible-soon
             ".article-view div.ui.segment.article-nav"))])
   (dispatch [:require item])
   (dispatch [:reload item])))

(sr-defroute
 project-user "/project/user" []
 (let [user-id @(subscribe [:self/user-id])
       item [:member/articles user-id]
       set-panel [:set-active-panel [:project :user :labels]
                  "/project/user"]]
   (if user-id
     (do (dispatch
          [:data/after-load item :user-articles-route
           (list set-panel
                 [:user-labels/hide-article]
                 #(ensure-dom-elt-visible-soon
                   ".article-list-view div.ui.segment.article-nav"))])
         (dispatch [:require item])
         (dispatch [:reload item]))
     (dispatch set-panel))))

(sr-defroute
 project-user-article "/project/user/article/:article-id" [article-id]
 (let [article-id (js/parseInt article-id)
       item [:article article-id]]
   (dispatch
    [:data/after-load item :user-articles-route
     (list [:set-active-panel [:project :user :labels]
            (str "/project/user/article/" article-id)]
           [:user-labels/show-article article-id]
           #(ensure-dom-elt-visible-soon
             ".article-view div.ui.segment.article-nav"))])
   (dispatch [:require item])
   (dispatch [:reload item])))

(sr-defroute
 project-labels "/project/labels" []
 (dispatch [:set-active-panel [:project :project :labels]
            "/project/labels"]))

(sr-defroute
 review "/project/review" []
 (let [set-panel-after #(dispatch
                         [:data/after-load % :review-route
                          [:set-active-panel [:project :review]
                           "/project/review"]])]
   (if-let [task-id @(subscribe [:review/task-id])]
     (do (set-panel-after [:article task-id])
         (dispatch [:reload [:article task-id]]))
     (do (set-panel-after [:review/task])))
   (dispatch [:require [:review/task]])))

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
 project-export "/project/export" []
 (dispatch [:set-active-panel [:project :project :export-data]
            "/project/export"]))

(sr-defroute
 login "/login" []
 (dispatch [:set-active-panel [:login]
            "/login"]))

(sr-defroute
 register-project "/register/:register-hash" [register-hash]
 (dispatch [:set-active-panel [:register]
            (str "/register/" register-hash)])
 (dispatch [:register/register-hash register-hash]))

(sr-defroute
 register-project-login "/register/:register-hash/login" [register-hash]
 (dispatch [:set-active-panel [:register]
            (str "/register/" register-hash "/login")])
 (dispatch [:register/register-hash register-hash])
 (dispatch [:register/login? true])
 (dispatch [:set-login-redirect-url (str "/register/" register-hash)]))

(sr-defroute
 request-password-reset "/request-password-reset" []
 (dispatch [:set-active-panel [:request-password-reset]
            "/request-password-reset"]))

(sr-defroute
 reset-password "/reset-password/:reset-code" [reset-code]
 (dispatch [:set-active-panel [:reset-password]
            (str "/reset-password/" reset-code)])
 (dispatch [:reset-password/reset-code reset-code]))

(sr-defroute
 select-project "/select-project" []
 (dispatch [:set-active-panel [:select-project]
            "/select-project"]))

(sr-defroute
 user-settings "/user/settings" []
 (dispatch [:set-active-panel [:user-settings]
            "/user/settings"]))

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

        [[:project :project :export-data]
         "/project/export"]

        [[:login]
         "/login"]

        [[:select-project]
         "/select-project"]

        [[:user-settings]
         "/user/settings"]]
       (reduce (fn [db [prefix uri]]
                 (set-subpanel-default-uri db prefix uri))
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
