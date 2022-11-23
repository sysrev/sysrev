(ns sysrev.views.panels.org.main
  (:require ["@insilica/react-components" :refer (Tab)]
            [clojure.string :as str]
            [cljs-time.coerce :as tc]
            [medley.core :refer [find-first]]
            [re-frame.core :refer [dispatch reg-event-db reg-sub subscribe
                                   trim-v]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data load-data reload]]
            [sysrev.macros :refer-macros [setup-panel-state with-loader def-panel]]
            [sysrev.markdown :as markdown]
            [sysrev.util :as util]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.org.users :refer [AddModal ChangeRoleModal
                                                   OrgInviteUrlModal RemoveModal]]
            [sysrev.views.semantic :refer
             [Message MessageHeader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org]
                   :state state :get [panel-get] :set [panel-set])

(def-data ::orgs
  :loaded?  (fn [db] (-> (panel-get db)
                         (contains? :orgs)))
  :uri      (constantly "/api/orgs")
  :process  (fn [{:keys [db]} [] {:keys [orgs]}]
              {:db (panel-set db [:orgs] orgs)
               :dispatch [:self/orgs orgs]})
  :on-error (fn [{:keys [db error]} [] _]
              {:db (panel-set db [:orgs-error] (:message error))}))
(reg-sub  ::orgs #(panel-get % [:orgs]))

(reg-sub      ::org-id #(panel-get % [:org-id]))
(reg-event-db ::org-id [trim-v]
              (fn [db [org-id]] (panel-set db :org-id org-id)))

(reg-sub ::org
         :<- [::orgs]
         (fn [orgs [_ org-id]] (->> orgs (find-first #(= (:group-id %) org-id)))))

(reg-sub :org/name
         (fn [[_ org-id]] (subscribe [::org org-id]))
         #(:group-name %))

(reg-sub :org/permissions
         (fn [[_ org-id]] (subscribe [::org org-id]))
         #(:permissions %))

(reg-sub :org/owner?
         (fn [[_ org-id _match-dev?]]
           [(subscribe [:org/permissions org-id])
            (subscribe [:user/dev?])])
         (fn [[permissions dev?] [_ _ match-dev? _]]
           (boolean (or (some #{"owner"} permissions)
                        (and match-dev? dev?)))))

(reg-sub :org/admin?
         (fn [[_ org-id _match-dev?]]
           [(subscribe [:org/permissions org-id])
            (subscribe [:user/dev?])])
         (fn [[permissions dev?] [_ _ match-dev? _]]
           (boolean (or (some #{"admin"} permissions)
                        (and match-dev? dev?)))))

(reg-sub :org/owner-or-admin?
         (fn [[_ org-id match-dev?]]
           [(subscribe [:org/owner? org-id match-dev?])
            (subscribe [:org/admin? org-id match-dev?])])
         (fn [[owner? admin?]]
           (or owner? admin?)))

(defn member->js [{:keys [permissions user-id username]}]
  #js{:name username
      :role (or (some #{"admin"} permissions) "member")
      :url (str js/window.location.origin "/user/" user-id "/profile")
      :userId user-id})

(defn project->js [{:keys [last-active markdown-description member-count project-id name settings]}]
  #js{:descriptionHtml (markdown/create-markdown-html markdown-description)
      :isPublic (boolean (:public-access settings))
      :lastActive (some-> last-active tc/from-date util/time-elapsed-string)
      :link (str js/window.location.origin "/p/" project-id)
      :members member-count
      :projectId project-id
      :title name})

(defn open-modal [modal-ref user-ref user]
  (reset! modal-ref  true)
  (reset! user-ref user))

(defn- OrgContent [org-id]
  (r/with-let [inviteModalOpen? (r/atom false)
               addModalOpen? (r/atom false)
               removeModalOpen? (r/atom false)
               changeModalOpen? (r/atom false)
               user-to-update (r/atom nil)]
    (let [active-panel @(subscribe [:active-panel])
          active? #(= active-panel [:org %])
          users @(subscribe [:org/users org-id])
          projects @(subscribe [:org/projects org-id])
          project-descriptions (mapv #(subscribe [:project/markdown-description (:project-id %)]) projects)]
      (doseq [{:keys [project-id]} projects]
        (load-data :project/markdown-description project-id))
      [:div
       [OrgInviteUrlModal inviteModalOpen? org-id]
       [AddModal addModalOpen? org-id]
       [RemoveModal removeModalOpen? org-id user-to-update]
       [ChangeRoleModal changeModalOpen? org-id user-to-update]
       (when-not (some active? #{:plans :payment})
         [:div
          [:f> Tab
           (clj->js
            {:darkMode (boolean @(subscribe [:self/dark-theme?]))
             :projects (->>
                        (map #(assoc % :markdown-description @%2) projects project-descriptions)
                        (sort-by (comp str/lower-case :name))
                        (mapv project->js))
             :members (mapv member->js users)
             :title @(subscribe [:org/name org-id])
             :url nil
             :logoImgUrl nil
             :orgId org-id
             :userIsAdmin true
             :inviteUrl #(reset! inviteModalOpen? true)
             :addMember #(reset! addModalOpen? true)
             :changeRole #(open-modal changeModalOpen? user-to-update (js->clj % :keywordize-keys true))
             :removeFromOrganization #(open-modal removeModalOpen? user-to-update (js->clj % :keywordize-keys true))})]])
       (when (nil? org-id)
         [Message {:negative true}
          [MessageHeader {:as "h4"} "Organizations Error"]
          [:p "This page does not exist."]])])))

(defn on-navigate-org [org-id to-panel]
  (let [_from-panel @(subscribe [:active-panel])]
    (dispatch [:set-panel-field [] {} to-panel])
    (dispatch [::org-id org-id])
    (dispatch [:set-active-panel to-panel])
    (dispatch [:data/load [:org/projects org-id]])
    (dispatch [:data/load [:org/users org-id]])
    (reload :org/available-plans)
    (reload :org/default-source org-id)
    (reload :org/current-plan org-id)))

(defmethod panel-content [:org] []
  (fn []
    (when-let [org-id @(subscribe [::org-id])]
      (with-loader [[::orgs]] {}
        [OrgContent org-id]))))



; this is required for routing to work properly
(def-panel :uri "/org/:org-id/users" :params [org-id]
  :on-route (let [org-id (util/parse-integer org-id)]
               (on-navigate-org org-id panel)
               (reload :org/users org-id)))
