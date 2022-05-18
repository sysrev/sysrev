(ns sysrev.state.project.core
  (:require [medley.core :as medley :refer [find-first]]
            [re-frame.core :refer [reg-sub subscribe]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :as nav]))

(reg-sub :project/name
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:name %))

(reg-sub :project/files
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:files %))

(reg-sub :project/invite-url
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [{:keys [invite-code]}]
           (str (nav/current-url-base) "/register/" invite-code)))

(defn- project-active-url-id-impl [project-id project self-projects]
  (let [project-url (-> project :url-ids first :url-id)
        self-url (->> self-projects
                      (filter #(= (:project-id %) project-id))
                      first :url-ids first)]
    (or project-url self-url)))

(reg-sub :project/active-url-id
         (fn [[_ project-id]]
           [(subscribe [:project/raw project-id])
            (subscribe [:self/projects true])])
         (fn [[project self-projects] [_ project-id]]
           (project-active-url-id-impl project-id project self-projects)))

(reg-sub :project/settings
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:settings %))

(reg-sub :project/public-access?
         (fn [[_ project-id]] (subscribe [:project/settings project-id]))
         #(:public-access %))

(defn get-source-by-id [sources source-id]
  (first (->> sources (filter #(= (:source-id %) source-id)))))

;; TODO: this should be a map, not a sequence
(reg-sub :project/sources
         (fn [[_ _ project-id]] (subscribe [:project/raw project-id]))
         (fn [project [_ source-id _]]
           (cond-> (:sources project)
             source-id (get-source-by-id source-id))))

(reg-sub :project/source-ids
         (fn [[_ _ project-id]] (subscribe [:project/sources project-id]))
         (fn [sources [_ enabled? _]]
           (let [include? (cond (true? enabled?)   true?
                                (false? enabled?)  false?
                                :else              (constantly true))]
             (->> sources
                  (filter #(-> % :enabled include?))
                  (mapv :source-id)))))

(reg-sub :project/keywords
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:keywords %))

(def-action :join-project
  :uri (fn [_] "/api/join-project")
  :content (fn [invite-code] {:invite-code invite-code})
  :process (fn [_ _ {:keys [project-id]}]
             {:dispatch-n (list [:fetch [:identity]]
                                [:fetch [:project project-id]]
                                [:project/navigate project-id])}))

(def-action :project/delete-file
  :uri (fn [project-id file-key] (str "/api/files/" project-id "/delete/" file-key))
  :process (fn [_ [project-id _] _]
             {:dispatch [:reload [:project/files project-id]]}))

(def-action :sources/delete
  :uri (fn [_ _] "/api/delete-source")
  :content (fn [project-id source-id]
             {:project-id project-id :source-id source-id})
  :process (fn [_ [project-id _] {:keys [success]}]
             (when success
               {:dispatch-n (list [:reload [:project project-id]]
                                  [:reload [:project/sources project-id]])}))
  :hide-loading true)

(reg-sub :project/controlled-by?
         (fn [[_ project-id user-id]]
           [(subscribe [:member/admin? false user-id project-id])
            (subscribe [:self/orgs user-id])
            (subscribe [:project/owner project-id])])
         (fn [[admin? orgs project-owner]]
           (let [{:keys [user-id group-id]} project-owner
                 org-permissions (:permissions
                                  (->> orgs (find-first #(= (:group-id %) group-id))))]
             ;; user owns project as admin, or admin/owner of org that owns project
             (or (and user-id admin?)
                 (and group-id (some #{"admin" "owner"} org-permissions))))))

(reg-sub :project/gengroups
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:gengroups %))
