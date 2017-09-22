(ns sysrev.data.definitions
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.subs.auth :refer [have-identity?]]
   [sysrev.subs.project :refer
    [project-loaded? active-project-id have-public-labels?]]
   [sysrev.subs.review :refer [task-id]]
   [sysrev.subs.articles :refer [have-article?]]
   [sysrev.subs.members :refer [have-member-articles?]]
   [sysrev.subs.ui :refer [active-panel]]
   [sysrev.shared.transit :as sr-transit]
   [sysrev.shared.util :refer [in?]]
   [sysrev.views.panels.login :refer [have-register-project?]]
   [sysrev.views.panels.password-reset :refer [have-reset-code?]]))

;;
;; Definitions for all data items fetched from server
;;

(def-data :identity
  :loaded-p have-identity?
  :uri (fn [] "/api/auth/identity")
  :process
  (fn [_ _ {:keys [identity active-project projects]}]
    {:dispatch-n
     (list [:self/set-identity identity]
           [:self/set-active-project active-project]
           [:self/set-projects projects]
           [:user/store identity])}))

(def-data :project
  :loaded-p project-loaded?
  :uri (fn [] "/api/project-info")
  :prereqs (fn [] [[:identity]])
  :process
  (fn [_ _ {:keys [project users]}]
    {:dispatch-n
     (list [:project/load project]
           [:user/store-multi (vals users)])}))

(def-data :project/settings
  :loaded-p project-loaded?
  :uri (fn [] "/api/project-settings")
  :prereqs (fn [] [[:identity]])
  :process
  (fn [{:keys [db]} _ {:keys [settings]}]
    (let [project-id (active-project-id db)]
      {:dispatch [:project/load-settings project-id settings]})))

(def-data :project/files
  :loaded-p project-loaded?
  :uri (fn [] "/api/files")
  :prereqs (fn [] [[:identity]])
  :process
  (fn [{:keys [db]} _ result]
    (let [project-id (active-project-id db)]
      (when (vector? result)
        {:dispatch [:project/load-files project-id result]}))))

(def-data :project/public-labels
  :loaded-p have-public-labels?
  :uri (fn [] "/api/public-labels")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} _ result]
    (let [result-decoded (sr-transit/decode-public-labels result)]
      {:dispatch [:project/load-public-labels result-decoded]})))

(def-data :member/articles
  :loaded-p have-member-articles?
  :uri (fn [user-id] (str "/api/member-articles/" user-id))
  :prereqs (fn [user-id] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} [user-id] result]
    (let [result-decoded (sr-transit/decode-member-articles result)]
      {:dispatch [:member/load-articles user-id result-decoded]})))

(def-data :review/task
  :loaded-p task-id
  :uri (fn [] "/api/label-task")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} _ {:keys [article labels notes today-count] :as result}]
    (if (= result :none)
      {:dispatch [:review/load-task :none nil]}
      (cond->
          {:dispatch-n
           (list [:article/load (merge article {:labels labels :notes notes})]
                 [:review/load-task (:article-id article) today-count])}

        (= (active-panel db) [:project :review])
        (merge {:scroll-top true})))))

(def-data :article
  :loaded-p have-article?
  :uri (fn [article-id] (str "/api/article-info/" article-id))
  :prereqs (fn [_] [[:identity] [:project]])
  :process
  (fn [_ [article-id] {:keys [article labels notes]}]
    {:dispatch [:article/load (merge article {:labels labels :notes notes})]}))

(def-data :register-project
  :loaded-p have-register-project?
  :uri (fn [_] "/api/query-register-project")
  :prereqs (fn [_] nil)
  :content (fn [register-hash] {:register-hash register-hash})
  :process
  (fn [_ [register-hash] {:keys [project]}]
    {:dispatch-n
     (list [:register/project-id register-hash (:project-id project)]
           [:register/project-name register-hash (:name project)])}))

(def-data :password-reset
  :loaded-p have-reset-code?
  :uri (fn [_] "/api/auth/lookup-reset-code")
  :prereqs (fn [_] nil)
  :content (fn [reset-code] {:reset-code reset-code})
  :process
  (fn [_ [reset-code] {:keys [email]}]
    (when email
      {:dispatch-n
       (list [:reset-password/reset-code reset-code]
             [:reset-password/email email])})))
