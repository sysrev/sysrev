(ns sysrev.routes
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
            [sysrev.nav :as nav :refer [nav nav-scroll-top nav-redirect]]
            [sysrev.state.nav :refer [set-subpanel-default-uri project-uri]]
            [sysrev.views.article-list.base :as article-list]
            [sysrev.views.panels.project.articles :as project-articles]
            [sysrev.views.panels.project.define-labels :as define-labels]
            [sysrev.shared.util :refer [parse-integer]]
            [sysrev.macros])
  (:require-macros [sysrev.macros :refer [sr-defroute sr-defroute-project]]))

(defn- go-project-panel [project-id]
  (let [panel [:project :project :overview]
        prev-panel @(subscribe [:active-panel])
        diff-panel (and prev-panel (not= panel prev-panel))
        markdown-item [:project/markdown-description
                       project-id {:panel panel}]]
    (dispatch [:set-active-panel panel])
    (dispatch [:require [:project project-id]])
    (dispatch [:require markdown-item])
    (dispatch [:require [:project/label-counts project-id]])
    (dispatch [:require [:project/important-terms project-id]])
    (dispatch [:require [:project/prediction-histograms project-id]])
    (when diff-panel
      (dispatch [:reload [:project project-id]])
      (dispatch [:reload markdown-item]))))

(sr-defroute
 home "/" []
 (dispatch [:set-active-panel [:root]])
 (dispatch [:require [:identity]])
 (dispatch [:reload [:identity]])
 (dispatch [:reload [:public-projects]]))

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
       context (project-articles/get-context)
       active-panel @(subscribe [:active-panel])
       panel-changed? (not= panel active-panel)
       data-item @(subscribe [::article-list/articles-query context])
       set-panel [:set-active-panel panel]
       have-project? @(subscribe [:have? [:project project-id]])
       load-params [:article-list/load-url-params context]
       sync-params #(article-list/sync-url-params context)
       set-transition [::article-list/set-recent-nav-action
                       context :transition]]
   (cond
     (not have-project?)
     (do (dispatch [:require [:project project-id]])
         (dispatch
          [:data/after-load [:project project-id] :project-articles-project
           (list load-params set-panel)]))

     panel-changed?
     (do (dispatch
          [:data/after-load data-item :project-articles-route
           (list set-panel #(js/setTimeout sync-params 25))])
         (dispatch set-transition)
         (article-list/require-list context)
         (article-list/reload-list context))

     :else (do (dispatch load-params)))))

(sr-defroute-project
 article-id "/article/:article-id" [project-id article-id]
 (let [project-id @(subscribe [:active-project-id])
       panel [:project :project :single-article]
       article-id (parse-integer article-id)
       item [:article project-id article-id]
       have-project? @(subscribe [:have? [:project project-id]])
       set-panel [:set-active-panel panel]
       set-article [:article-view/set-active-article article-id]]
   (if (integer? article-id)
     (do (if (not have-project?)
           (do (dispatch set-panel) (dispatch set-article))
           (dispatch [:data/after-load item :article-route
                      (list set-panel set-article)]))
         (dispatch [:require item])
         (dispatch [:reload item]))
     (do (js/console.log "invalid article id")
         (nav-scroll-top "/")))))

(sr-defroute-project
 project-labels-edit "/labels/edit" [project-id]
 (define-labels/ensure-state)
 (dispatch [:set-active-panel [:project :project :labels :edit]]))

(sr-defroute-project
 review "/review" [project-id]
 (let [project-id @(subscribe [:active-project-id])
       panel [:project :review]
       have-project? @(subscribe [:have? [:project project-id]])
       set-panel [:set-active-panel panel]
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
 project-export "/export" [project-id]
 (dispatch [:set-active-panel [:project :project :export-data]]))

(sr-defroute-project
 project-compensations "/compensations" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (dispatch [:set-active-panel [:project :project :compensations]])))

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
 pubmed-search "/pubmed-search" []
 (dispatch [:set-active-panel [:pubmed-search]]))

(sr-defroute-project
 support "/support" [project-id]
 (let [project-id @(subscribe [:active-project-id])]
   (dispatch [:set-active-panel [:project :project :support]])))

(sr-defroute
 plans "/user/plans" []
 (dispatch [:set-active-panel [:plans]]))

(sr-defroute
 plans "/user/payment" []
 (dispatch [:set-active-panel [:payment]]))

(sr-defroute
 user-settings "/user/settings*" []
 (dispatch [:set-active-panel [:user-settings]]))

(sr-defroute
 users "/users*" []
 (dispatch [:set-active-panel [:users]]))

(defn- load-default-panels [db]
  (->> [[[]
         "/"]

        [[:project]
         #(project-uri (:project-id %) "")]

        [[:project :project :overview]
         #(project-uri (:project-id %) "")]

        [[:project :project :articles]
         #(project-uri (:project-id %) "/articles")]

        [[:project :project :single-article]
         #(project-uri (:project-id %) "/article")]

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

        [[:project :project :compensations]
         #(project-uri (:project-id %) "/compensations")]

        [[:project :project :export-data]
         #(project-uri (:project-id %) "/export")]

        [[:login]
         "/login"]

        [[:request-password-reset]
         "/request-password-reset"]

        [[:pubmed-search]
         "/pubmed-search"]

        [[:payment]
         "/user/payment"]

        [[:project :project :support]
         #(project-uri (:project-id %) "/support")]

        [[:plans]
         "/user/plans"]

        [[:user-settings]
         "/user/settings*"]

        [[:users]
         "/users*"]]
       (reduce (fn [db [prefix uri]]
                 (set-subpanel-default-uri db prefix uri))
               db)))

(reg-event-db :ui/load-default-panels load-default-panels)
