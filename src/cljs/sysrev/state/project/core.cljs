(ns sysrev.state.project.core
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.action.core :refer [def-action]]
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
   (str "https://sysrev.com/register/" project-hash)))

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

(defn get-project-uri [db project-id suburi]
  (let [project-url-id (project-active-url-id db project-id)
        url-id (if (string? project-url-id)
                 project-url-id project-id)]
    (str "/p/" url-id suburi)))

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

;; TODO: disable after new article list ready
(reg-sub
 :project/public-labels
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:public-labels project)))

(reg-sub
 :project/document-paths
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ document-id _]]
   (if (nil? document-id)
     (get-in project [:documents])
     (get-in project [:documents document-id]))))

(reg-sub
 :project/sources
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:sources project)))

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

(def-action :create-project
  :uri (fn [_] "/api/create-project")
  :content (fn [project-name] {:project-name project-name})
  :process (fn [_ _ {:keys [success message project] :as result}]
             (if success
               {:dispatch-n
                (list [:fetch [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

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
