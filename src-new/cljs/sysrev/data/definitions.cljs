(ns sysrev.data.definitions
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.subs.auth :refer [have-identity?]]
   [sysrev.subs.project :refer [project-loaded? active-project-id]]
   [sysrev.subs.label-activity :refer [have-label-activity?]]
   [sysrev.subs.review :refer [task-id]]
   [sysrev.subs.articles :refer [have-article?]]))

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

(def-data :project/label-activity
  :loaded-p have-label-activity?
  :uri (fn [label-id] (str "/api/label-activity/" label-id))
  :prereqs (fn [label-id] [[:identity] [:project]])
  :process
  (fn [_ [label-id] result]
    {:dispatch [:project/load-label-activity label-id result]}))

(def-data :review/task
  :loaded-p task-id
  :uri (fn [] "/api/label-task")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [_ _ {:keys [article labels notes today-count]}]
    {:dispatch-n
     (list [:article/load (merge article {:labels labels :notes notes})]
           [:review/load-task (:article-id article) today-count])}))

(def-data :article
  :loaded-p have-article?
  :uri (fn [article-id] (str "/api/article-info/" article-id))
  :prereqs (fn [_] [[:identity] [:project]])
  :process
  (fn [_ [article-id] {:keys [article labels notes]}]
    {:dispatch [:article/load (merge article {:labels labels :notes notes})]}))
