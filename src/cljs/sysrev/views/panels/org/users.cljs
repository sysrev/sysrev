(ns sysrev.views.panels.org.users
  (:require [clojure.set :as set]
            [medley.core :refer [find-first]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :as data :refer [def-data load-data]]
            [sysrev.nav :as nav]
            [sysrev.views.semantic :as S :refer
             [Table TableBody TableRow TableCell Search Button
              Modal ModalHeader ModalContent ModalDescription Form FormGroup Checkbox
              Input]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :users]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :org/users
  :uri (fn [org-id] (str "/api/org/" org-id "/users"))
  :loaded? (fn [db org-id] (-> (get-in db [:org org-id])
                               (contains? :users)))
  :process (fn [{:keys [db]} [org-id] {:keys [users]}]
             {:db (assoc-in db [:org org-id :users] users)}))

(def-action :org/get-share-code
  :uri (fn [org-id] (str "/api/org/" org-id "/get-share-code"))
  :content (fn [org-id]
             {:org-id org-id})
  :process (fn [_ [_] {:keys [success share-code]}]
             (when success
               {:dispatch [::set [:org-invite-url-modal :share-code] share-code]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:alert {:opts {:error true}
                                  :content (str "There was an error generating the invite URL")}]}))

(defn org-users [db org-id] (get-in db [:org org-id :users]))

(reg-sub :org/users (fn [db [_ org-id]] (org-users db org-id)))

(def-data :user-search
  :loaded?  (fn [db term]
              (-> (get-in db [:data :user-search])
                  (contains? term)))
  :uri      (constantly "/api/users/search")
  :content  (fn [term] {:term term})
  :process  (fn [{:keys [db]} [term] {:keys [users]}]
              {:db (assoc-in db [:data :user-search term] users)}))

(reg-sub  :user-search
          (fn [db [_ term]]
            (get-in db [:data :user-search term])))

(reg-sub  ::user-search-results
          (fn [db [_ term org-id]]
            (when-let [search-result (get-in db [:data :user-search term])]
              (->> (set/difference
                    (set search-result)
                    (set (->> (org-users db org-id)
                              (map #(dissoc % :primary-email-verified)))))
                   (mapv #(-> % (assoc :key (:user-id %)
                                       :title "<empty>")))))))

(def-action :org/add-user
  :method   :post
  :uri      (fn [org-id _] (str "/api/org/" org-id "/user"))
  :content  (fn [_ user-id] {:user-id user-id})
  :process  (fn [{:keys [db]} [org-id _] _result]
              {:db (panel-set db [:add-user] {})
               :dispatch [:data/load [:org/users org-id]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db [:add-user :error] (:message error))}))

(def-action :org/remove-user
  :method   :delete
  :uri      (fn [org-id _ _] (str "/api/org/" org-id "/user"))
  :content  (fn [_ user-id _] {:user-id user-id})
  :process  (fn [{:keys [db]} [org-id _ modal-open] _result]
              (reset! modal-open false)
              {:db (panel-set db [:remove-user] {})
               :dispatch [:data/load [:org/users org-id]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db [:remove-user :error] (:message error))}))

(def-action :org/change-user-role
  :method   :put
  :uri      (fn [org-id _ _ _] (str "/api/org/" org-id "/user"))
  :content  (fn [_ user-id permissions _]
              {:user-id user-id :permissions permissions})
  :process  (fn [{:keys [db]} [org-id _ _ modal-open] _result]
              (reset! modal-open false)
              {:db (panel-set db [:change-role] {})
               :dispatch [:data/load [:org/users org-id]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db [:change-role :error] (:message error))}))

(defn OrgInviteUrlModal [modal-open org-id]
  (let [modal-state-path [:org-invite-url-modal]
        share-code (r/cursor state (concat modal-state-path [:share-code]))]
    (dispatch [:action [:org/get-share-code org-id]])
    (fn []
      [Modal {:open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Invite URL"]
       [ModalContent
        (if @share-code
          [:div
           [:p "Copy this URL to invite members:"]
           [:div.ui.card.fluid {:style {:margin-bottom "16px"}}
            [:div.content
             [:span.share-code.ui.text.black
              (str (nav/current-url-base) "/register/" @share-code)]]]]
          [:div.ui.segment {:style {:height "100px"}}
           [:div.ui.active.dimmer
            [:div.ui.loader]]])
        [ModalDescription
         [Button {:primary true
                  :on-click (util/wrap-prevent-default
                              #(reset! modal-open false))
                  :id "close-invite-org-btn"}
          "OK"]]]])))

(defn AddModal [modal-open org-id]
  (let [search-value    (r/cursor state [:add-user :search-value])
        user-id-select  (r/cursor state [:add-user :user-id])
        error           (r/cursor state [:add-user :error])
        search-results  @(subscribe [::user-search-results @search-value org-id])
        user-id-match   (:user-id (find-first #(= (:username %) @search-value)
                                              search-results))
        user-id-active  (or @user-id-select user-id-match)]
    [Modal {:open @modal-open
            :on-open #(reset! modal-open true)
            :on-close #(reset! modal-open false)}
     [ModalHeader (str "Invite Member to " @(subscribe [:org/name org-id]))]
     [ModalContent
      [ModalDescription
       [Form {:id "invite-member-form"
              :on-submit (fn [_e]
                           (when user-id-active
                             (run-action :org/add-user org-id user-id-active)))}
        [:div
         [Search
          {:id "org-search-users-input"
           :placeholder "Search for users by username"
           :auto-focus true
           :loading (data/loading? :user-search)
           :on-result-select
           (fn [_e value]
             (let [{:keys [result]} (js->clj value :keywordize-keys true)
                   {:keys [user-id username]} result]
               (reset! user-id-select user-id)
               (reset! search-value username)
               (when (seq username)
                 (load-data :user-search username))))
           :on-search-change
           (fn [_e value]
             (let [input-value (.-value value)]
               (reset! user-id-select nil)
               (reset! search-value input-value)
               (when (seq input-value)
                 (load-data :user-search input-value))))
           :result-renderer
           (fn [item]
             (let [{:keys [user-id username]} (js->clj item :keywordize-keys true)]
               (r/as-element [:div {:style {:display "flex"}}
                              [Avatar {:user-id user-id}]
                              [:p username]])))
           :results search-results
           :value (or @search-value "")
           :input (r/as-element
                   [Input {:placeholder "Search for users by username"
                           :action (r/as-element
                                    [Button {:id "submit-add-member" :class "invite-member"
                                             :positive true
                                             :disabled (nil? user-id-active)}
                                     "Add Member"])}])}]]
        [CursorMessage error {:negative true}]]]]]))

(defn RemoveModal [modal-open org-id user]
  (let [error      (r/cursor state [:remove-user :error])
        running?   (action/running? :org/remove-user)]
    [Modal {:open @modal-open
            :on-open #(reset! modal-open true)
            :on-close #(reset! modal-open false)}
     [ModalHeader (str "Removing 1 member from " @(subscribe [:org/name org-id]))]
     [ModalContent
      [ModalDescription
       [Form {:on-submit #(run-action :org/remove-user org-id (:userId @user) modal-open)}
        [:h4 "The following members will be removed:"]
        [Table {:basic true :style {:width "50%"}}
         [TableBody
          [TableRow
           [TableCell
            [Avatar {:user-id (:userId @user)}]
            [UserPublicProfileLink {:user-id (:userId @user) :username (:name @user)}]]]]]
        [Button {:color "orange" :disabled running?}
         "Remove members"]
        [CursorMessage error {:negative true}]]]]]))

(defn ChangeRoleModal [modal-open org-id user]
  (let [new-role   (r/cursor state [:change-role :new-role])
        error      (r/cursor state [:change-role :error])
        running?   (action/running? :org/change-user-role)]
    [Modal {:open @modal-open
            :on-open #(reset! modal-open true)
            :on-close #(reset! modal-open false)}
     [ModalHeader (str "Change the role of " (:name @user) "?")]
     [ModalContent
      [ModalDescription
       [Form {:on-submit #(run-action :org/change-user-role org-id (:userId @user) [@new-role] modal-open)}
        [FormGroup
         [Checkbox {:label "Admin"
                    :as "h4", :radio true, :style {:display "block"}
                    :checked (= @new-role "admin")
                    :on-change #(reset! new-role "admin")}]]
        [:p {:style {:margin-top "0" :margin-left "1.5rem"}}
         "Has full administrative access to all organization projects. "
         "Can update payment information. "
         "Can add, remove, and edit projects."]
        [FormGroup
         [Checkbox {:label "Member"
                    :as "h4", :radio true, :style {:display "block"}
                    :checked (= @new-role "member")
                    :on-change #(reset! new-role "member")}]]
        [:p {:style {:margin-top "0" :margin-left "1.5rem"}}
         "Can see every member and project in the organization"]
        [Button {:id "org-change-role-button"
                 :type "submit", :color "orange"
                 :disabled (or (nil? @new-role) running?)}
         "Change Role"]
        [CursorMessage error {:negative true}]]]]]))
