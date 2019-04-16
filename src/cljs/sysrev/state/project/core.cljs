(ns sysrev.state.project.core
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :as nav]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.state.identity :refer [get-self-projects]]
            [sysrev.shared.util :refer [short-uuid]]))

(reg-sub
 :project/name
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:name project)))

(reg-sub
 :project/files
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:files project)))

(reg-sub
 :project/uuid
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:project-uuid project)))

(reg-sub
 :project/hash
 (fn [[_ project-id]]
   [(subscribe [:project/uuid project-id])])
 (fn [[project-uuid]]
   (when project-uuid
     (short-uuid project-uuid))))

(reg-sub
 :project/invite-url
 (fn [[_ project-id]]
   [(subscribe [:project/hash project-id])])
 (fn [[project-hash]]
   (str (nav/current-url-base) "/register/" project-hash)))

(defn- project-active-url-id-impl [project-id project self-projects]
  (let [project-url
        (-> project :url-ids first :url-id)
        self-url
        (->> self-projects
             (filter #(= (:project-id %) project-id))
             first :url-ids first)]
    (or project-url self-url)))
;;
(defn project-active-url-id [db project-id]
  (let [project (get-project-raw db project-id)
        self-projects (get-self-projects db :include-available? true)]
    (project-active-url-id-impl project-id project self-projects)))
;;
(reg-sub
 :project/active-url-id
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])
    (subscribe [:self/projects true])])
 (fn [[project self-projects] [_ project-id]]
   (project-active-url-id-impl project-id project self-projects)))

(reg-sub
 :project/settings
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:settings project)))

(reg-sub
 :project/public-access?
 (fn [[_ project-id]]
   [(subscribe [:project/settings project-id])])
 (fn [[settings]] (:public-access settings)))

(reg-sub
 :project/document-paths
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ document-id _]]
   (if (nil? document-id)
     (get-in project [:documents])
     (get-in project [:documents document-id]))))

(defn get-source-by-id [sources source-id]
  (->> sources
       (filter #(= (:source-id %) source-id))
       first))

;; TODO: this should be a map, not a sequence
(reg-sub
 :project/sources
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ source-id _]]
   (cond-> (:sources project)
     source-id (get-source-by-id source-id))))

(reg-sub
 :project/source-ids
 (fn [[_ _ project-id]]
   [(subscribe [:project/sources project-id])])
 (fn [[sources] [_ enabled? _]]
   (let [include? (cond (true? enabled?)   true?
                        (false? enabled?)  false?
                        :else              (constantly true))]
     (->> sources
          (filter #(-> % :enabled include?))
          (mapv :source-id)))))

(reg-sub
 :project/keywords
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:keywords project)))

(reg-sub
 :project/notes
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ project-id note-name]]
   (cond-> (:notes project)
     note-name (get note-name))))

(def-action :join-project
  :uri (fn [_] "/api/join-project")
  :content (fn [id] {:project-id id})
  :process (fn [_ [id] result]
             {:dispatch-n
              (list [:fetch [:identity]]
                    [:fetch [:project id]]
                    [:project/navigate id])}))

(def-action :project/delete-file
  :uri (fn [project-id file-id] (str "/api/files/" project-id "/delete/" file-id))
  :process (fn [_ [project-id _] result]
             {:dispatch [:reload [:project/files project-id]]}))

(def-action :sources/delete
  :uri (fn [_ _] "/api/delete-source")
  :content (fn [project-id source-id]
             {:project-id project-id :source-id source-id})
  :process
  (fn [_ [project-id _] {:keys [success] :as result}]
    (when success
      {:dispatch-n
       (list [:reload [:project project-id]]
             [:reload [:project/sources project-id]])})))
