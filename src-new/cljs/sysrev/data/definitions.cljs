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
   [sysrev.subs.labels :refer [from-label-local-id]]
   [sysrev.subs.ui :refer [active-panel]]
   [sysrev.shared.transit :as sr-transit]
   [sysrev.shared.util :refer [in?]]))

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

(def-data :project/public-labels
  :loaded-p have-public-labels?
  :uri (fn [] "/api/public-labels")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} _ result]
    (let [from-local-id (from-label-local-id db)
          result-decoded (sr-transit/decode-public-labels result from-local-id)]
      {:dispatch [:project/load-public-labels result-decoded]})))

(def-data :member/articles
  :loaded-p have-member-articles?
  :uri (fn [user-id] (str "/api/member-articles/" user-id))
  :prereqs (fn [user-id] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} [user-id] result]
    (let [from-local-id (from-label-local-id db)
          result-decoded (sr-transit/decode-member-articles result from-local-id)]
      {:dispatch [:member/load-articles user-id result-decoded]})))

(def-data :review/task
  :loaded-p task-id
  :uri (fn [] "/api/label-task")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} _ {:keys [article labels notes today-count]}]
    (cond->
        {:dispatch-n
         (list [:article/load (merge article {:labels labels :notes notes})]
               [:review/load-task (:article-id article) today-count])}

      (= (active-panel db) [:project :review])
      (merge {:scroll-top true}))))

(def-data :article
  :loaded-p have-article?
  :uri (fn [article-id] (str "/api/article-info/" article-id))
  :prereqs (fn [_] [[:identity] [:project]])
  :process
  (fn [_ [article-id] {:keys [article labels notes]}]
    {:dispatch [:article/load (merge article {:labels labels :notes notes})]}))
