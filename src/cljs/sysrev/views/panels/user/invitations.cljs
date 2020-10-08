(ns sysrev.views.panels.user.invitations
  (:require ["moment" :as moment]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.views.semantic :refer [Segment Button Grid Row Column]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.util :as util :refer [index-by space-join parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:user :invitations]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :user/invitations
  :loaded?  (fn [db user-id]
              (-> (get-in db [:data :user-invitations])
                  (contains? user-id)))
  :uri      (fn [user-id] (str "/api/user/" user-id "/invitations"))
  :process  (fn [{:keys [db]} [user-id] {:keys [invitations]}]
              {:db (assoc-in db [:data :user-invitations user-id]
                             (index-by :id invitations))}))
(reg-sub  :user/invitations
          (fn [db [_ user-id]]
            (let [user-id (or user-id (current-user-id db))]
              (get-in db [:data :user-invitations user-id]))))

(def-action :user/update-invitation
  :method   :put
  :uri      (fn [self-id inv-id _]
              (str "/api/user/" self-id "/invitation/" inv-id))
  :content  (fn [_ _ accepted?] {:accepted accepted?})
  :process  (fn [{:keys [db]} [self-id inv-id accepted?] _result]
              {:db (-> (assoc-in db [:data :user-invitations self-id inv-id :accepted]
                                 accepted?)
                       (panel-set :update-error nil))
               :dispatch [:data/load [:user/invitations self-id]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db :update-error (:message error))}))

(defn- Invitation [{:keys [id project-id project-name description accepted
                           active created updated]}]
  (let [self-id @(subscribe [:self/user-id])
        update-error (r/cursor state [:update-error])
        running? (action/running? :user/update-invitation)]
    [Segment
     [Grid
      [Row
       [Column {:width 8} [:h3 project-name]]
       [Column {:width 8} [:h5 {:style {:text-align "right"}}
                           (-> created (moment.) (.format "YYYY-MM-DD h:mm A"))]]]
      [Row
       [Column {:width 16}
        (when (some? accepted)
          [:div (space-join ["You" (if accepted "accepted" "declined")
                             "this invitation on" (-> updated (moment.) (.format "YYYY-MM-DD"))
                             "at" (-> updated (moment.) (.format "h:mm A"))])])]]]
     (when (nil? accepted)
       [:div
        [:h3 (str "You've been invited as a " description ".")]
        [Button {:on-click #(run-action :user/update-invitation self-id id true)
                 :color "green"
                 :disabled running?
                 :size "mini"} "Accept"]
        [Button {:on-click #(run-action :user/update-invitation self-id id false)
                 :color "orange"
                 :disabled running?
                 :size "mini"} "Decline"]])
     [CursorMessage update-error {:negative true}]]))

(defn UserInvitations []
  (when-let [self-id @(subscribe [:self/user-id])]
    (with-loader [[:user/invitations self-id]] {}
      (let [invitations @(subscribe [:user/invitations self-id])]
        [:div (if (seq invitations)
                (doall (for [invitation (->> (vals invitations)
                                             #_ (filter #(nil? (:accepted %))))]
                         ^{:key (:id invitation)}
                         [Invitation invitation]))
                [Segment "You don't currently have any invitations to other projects"])]))))

(def-panel :uri "/user/:user-id/invitations" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (when user-id
                (dispatch [:reload [:user/invitations user-id]]))
              (dispatch [:set-active-panel panel]))
  :content [UserInvitations]
  :require-login true)
