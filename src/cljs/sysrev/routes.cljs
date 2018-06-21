(ns sysrev.routes
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
   [re-frame.db :refer [app-db]]
   [sysrev.util :refer [scroll-top ensure-dom-elt-visible-soon]]
   [sysrev.shared.util :refer [parse-integer]]
   [sysrev.state.nav :refer [set-subpanel-default-uri project-uri]]
   [sysrev.nav :refer [nav nav-scroll-top nav-redirect]]
   [sysrev.macros])
  (:require-macros [secretary.core :refer [defroute]]
                   [sysrev.macros :refer [sr-defroute sr-defroute-project]]))

(defn- go-project-panel [project-id]
  (let [panel [:project :project :overview]
        prev-panel @(subscribe [:active-panel])
        diff-panel (and prev-panel (not= panel prev-panel))]
    (dispatch [:set-active-panel panel])
    (when diff-panel
      (dispatch [:reload [:project project-id]]))))

(sr-defroute
 home "/" []
 (let [logged-in? (subscribe [:self/logged-in?])
       recent-project-id (subscribe [:recent-active-project])
       default-project-id (subscribe [:self/default-project-id])
       on-ready #(if @logged-in?
                   (let [project-id (or @recent-project-id
                                        @default-project-id)]
                     (if (integer? project-id)
                       (nav-redirect (project-uri project-id "")
                                     :scroll-top? true)
                       (nav-redirect "/select-project"
                                     :scroll-top? true)))
                   (nav-redirect "/login" :scroll-top? true))]
   (if @(subscribe [:have? [:identity]])
     (on-ready)
     (dispatch
      [:data/after-load [:identity] :default-route on-ready]))))

;;
;; project routes
;;

(sr-defroute-project
 project "" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (go-project-panel project-id)))

(sr-defroute-project
 articles "/articles" [project-id]
 (let [project-id @(subscribe [:active-project-id])
       panel [:project :project :articles]
       item [:project/public-labels project-id]
       set-panel [:set-active-panel [:project :project :articles]]
       ensure-visible #(ensure-dom-elt-visible-soon
                        ".article-list-view div.ui.segment.article-nav")
       on-article? (and (= @(subscribe [:active-panel]) panel)
                        @(subscribe [:public-labels/article-id]))
       data-loaded? @(subscribe [:have? item])
       have-project? @(subscribe [:have? [:project project-id]])]
   (when (not have-project?)
     (dispatch set-panel))
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

(sr-defroute-project
 articles-id "/articles/:article-id" [project-id article-id]
 (let [project-id @(subscribe [:active-project-id])
       article-id (parse-integer article-id)
       item [:article project-id article-id]
       set-panel [:set-active-panel [:project :project :articles]]
       have-project? @(subscribe [:have? [:project project-id]])]
   (when (not have-project?)
     (dispatch set-panel))
   (dispatch
    [:data/after-load item :project-articles-route
     (list set-panel
           [:public-labels/show-article article-id]
           #(ensure-dom-elt-visible-soon
             ".article-view div.ui.segment.article-nav"))])
   (dispatch [:require item])
   (dispatch [:reload item])
   (dispatch [:require [:pdf/article-pdfs article-id]])
   (dispatch [:reload [:pdf/article-pdfs article-id]])))

(sr-defroute-project
 project-user "/user" [project-id]
 (let [project-id @(subscribe [:active-project-id])
       user-id @(subscribe [:self/user-id])
       item [:member/articles project-id user-id]
       set-panel [:set-active-panel [:project :user :labels]]]
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

(sr-defroute-project
 project-user-article "/user/article/:article-id" [project-id article-id]
 (let [project-id @(subscribe [:active-project-id])
       article-id (parse-integer article-id)
       item [:article project-id article-id]
       set-panel [:set-active-panel [:project :user :labels]]
       have-project? @(subscribe [:have? [:project project-id]])]
   (when (not have-project?)
     (dispatch set-panel))
   (dispatch
    [:data/after-load item :user-articles-route
     (list set-panel
           [:user-labels/show-article article-id]
           #(ensure-dom-elt-visible-soon
             ".article-view div.ui.segment.article-nav"))])
   (dispatch [:require item])
   (dispatch [:reload item])
   (dispatch [:require [:pdf/article-pdfs article-id]])
   (dispatch [:reload [:pdf/article-pdfs article-id]])))

(sr-defroute-project
 project-labels-edit "/labels/edit" [project-id]
 (dispatch [:set-active-panel [:project :project :labels :edit]]))

(sr-defroute-project
 review "/review" [project-id]
 (let [project-id @(subscribe [:active-project-id])
       have-project? @(subscribe [:have? [:project project-id]])
       set-panel [:set-active-panel [:project :review]]
       set-panel-after #(dispatch
                         [:data/after-load % :review-route set-panel])]
   (when (not have-project?)
     (dispatch set-panel))
   (let [task-id @(subscribe [:review/task-id])]
     (if (integer? task-id)
       (do (set-panel-after [:article project-id task-id])
           (dispatch [:reload [:article project-id task-id]]))
       (do (set-panel-after [:review/task project-id])))
     (when (= task-id :none)
       (dispatch [:reload [:review/task project-id]]))
     (dispatch [:require [:review/task project-id]]))))

(sr-defroute-project
 manage-project "/manage" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (nav-redirect (project-uri project-id "/add-articles"))))

(sr-defroute-project
 add-articles "/add-articles" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (dispatch [:reload [:project/sources project-id]])
   (dispatch [:set-active-panel [:project :project :add-articles]])))

(sr-defroute-project
 project-settings "/settings" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (dispatch [:reload [:project/settings project-id]])
   (dispatch [:set-active-panel [:project :project :settings]])))

(sr-defroute-project
 invite-link "/invite-link" [project-id]
 (dispatch [:set-active-panel [:project :project :invite-link]]))

(sr-defroute-project
 project-export "/export" [project-id]
 (dispatch [:set-active-panel [:project :project :export-data]]))

;;
;; non-project routes
;;

(sr-defroute
 login "/login" []
 (dispatch [:set-active-panel [:login]]))

(sr-defroute
 register-user "/register" []
 (dispatch [:set-active-panel [:register]]))

(sr-defroute
 register-project "/register/:register-hash" [register-hash]
 (dispatch [:set-active-panel [:register]])
 (dispatch [:register/register-hash register-hash]))

(sr-defroute
 register-project-login "/register/:register-hash/login" [register-hash]
 (dispatch [:set-active-panel [:register]])
 (dispatch [:register/register-hash register-hash])
 (dispatch [:register/login? true])
 (dispatch [:set-login-redirect-url (str "/register/" register-hash)]))

(sr-defroute
 request-password-reset "/request-password-reset" []
 (dispatch [:set-active-panel [:request-password-reset]]))

(sr-defroute
 reset-password "/reset-password/:reset-code" [reset-code]
 (dispatch [:set-active-panel [:reset-password]])
 (dispatch [:reset-password/reset-code reset-code])
 (dispatch [:fetch [:password-reset reset-code]]))

(sr-defroute
 select-project "/select-project" []
 (dispatch [:set-active-panel [:select-project]])
 (dispatch [:reload [:identity]]))

(sr-defroute
 pubmed-search "/pubmed-search" []
 (dispatch [:set-active-panel [:pubmed-search]]))

(sr-defroute
 plans "/plans" []
 (dispatch [:reload [:plans]])
 (dispatch [:reload [:current-plan]])
 (dispatch [:set-active-panel [:plans]]))

(sr-defroute
 payment "/payment" []
 (dispatch [:set-active-panel [:payment]]))

(sr-defroute-project
 support "/support" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (dispatch [:set-active-panel [:project :project :support]])))

(sr-defroute
 user-settings "/user/settings" []
 (dispatch [:set-active-panel [:user-settings]]))

(defn- load-default-panels [db]
  (->> [[[]
         "/"]

        [[:project]
         #(project-uri (:project-id %) "")]

        [[:project :project :overview]
         #(project-uri (:project-id %) "")]

        [[:project :project :articles]
         #(project-uri (:project-id %) "/articles")]

        [[:project :project :manage]
         #(project-uri (:project-id %) "/manage")]

        [[:project :project :add-articles]
         #(project-uri (:project-id %) "/add-articles")]

        [[:project :user]
         #(project-uri (:project-id %) "/user")]

        [[:project :user :labels]
         #(project-uri (:project-id %) "/user")]

        [[:project :project :labels :edit]
         #(project-uri (:project-id %) "/labels/edit")]

        [[:project :review]
         #(project-uri (:project-id %) "/review")]

        [[:project :project :settings]
         #(project-uri (:project-id %) "/settings")]

        [[:project :project :invite-link]
         #(project-uri (:project-id %) "/invite-link")]

        [[:project :project :export-data]
         #(project-uri (:project-id %) "/export")]

        [[:login]
         "/login"]

        [[:request-password-reset]
         "/request-password-reset"]

        [[:select-project]
         "/select-project"]

        [[:pubmed-search]
         "/pubmed-search"]

        [[:plans]
         "/plans"]

        [[:payment]
         "/payment"]

        [[:project :project :support]
         #(project-uri (:project-id %) "/support")]

        [[:user-settings]
         "/user/settings"]]
       (reduce (fn [db [prefix uri]]
                 (set-subpanel-default-uri db prefix uri))
               db)))

(reg-event-db :ui/load-default-panels load-default-panels)
