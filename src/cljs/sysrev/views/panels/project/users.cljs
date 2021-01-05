(ns sysrev.views.panels.project.users
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.components.core :as ui]
            [sysrev.state.project.members :as members]
            [sysrev.views.semantic :as S :refer
             [Table TableBody TableRow TableHeader TableHeaderCell TableCell Search Button
              Modal ModalHeader ModalContent ModalDescription Form
              Input FormField TextArea]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :users]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(defn txt->emails [txt]
  (when (string? txt)
    (->> (str/split txt #"[ ,\n]")
         (map str/trim)
         (filter util/email?))))

(def-action :project/send-invites
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id emails-txt]
             (let [emails (txt->emails emails-txt)]
               {:project-id project-id
                :emails emails}))
  :process (fn [_ _ {:keys [success message]}]
             (when success
               {:dispatch-n [[::set [:invite-emails :emails-txt] ""]
                             [:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/create-member-gengroup
  :uri (fn [_ _] "/api/create-gengroup")
  :content (fn [project-id gengroup]
             {:project-id project-id
              :gengroup-name (:name gengroup)
              :gengroup-description (:description gengroup)})
  :process (fn [{:keys [db]} [project-id _] {:keys [success message]}]
             (when success
               {:dispatch-n [[::set [:gengroup-modal :new :open] false]
                             [:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/update-member-gengroup
  :uri (fn [_ _] "/api/update-gengroup")
  :content (fn [project-id gengroup]
             {:project-id project-id
              :gengroup-id (:gengroup-id gengroup)
              :gengroup-name (:name gengroup)
              :gengroup-description (:description gengroup)})
  :process (fn [{:keys [db]} [project-id gengroup] {:keys [success message]}]
             (when success
               {:dispatch-n [[::set [:gengroup-modal (:gengroup-id gengroup) :open] false]
                             [:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/delete-member-gengroup
  :uri (fn [_ _] "/api/delete-gengroup")
  :content (fn [project-id gengroup-id]
             {:project-id project-id
              :gengroup-id gengroup-id})
  :process (fn [{:keys [db]} [project-id gengroup-id] {:keys [success message]}]
             (when success
               {:dispatch-n [[:toast {:class "success" :message message}]
                             [::set [:delete-gengroup-modal gengroup-id :open] false]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/add-member-to-gengroup
  :uri (fn [_ _] "/api/add-member-to-gengroup")
  :content (fn [project-id gengroup-id membership-id]
             {:project-id project-id
              :gengroup-id gengroup-id
              :membership-id membership-id})
  :process (fn [_ [project-id _ _] {:keys [success message]}]
             (when success
               {:dispatch-n [[:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/remove-member-from-gengroup
  :uri (fn [_ _] "/api/remove-member-from-gengroup")
  :content (fn [project-id gengroup-id membership-id]
             {:project-id project-id
              :gengroup-id gengroup-id
              :membership-id membership-id})
  :process (fn [_ [project-id _ _] {:keys [success message]}]
             (when success
               {:dispatch-n [[:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(defn- InviteEmailsCmp []
  (let [project-id @(subscribe [:active-project-id])
        emails-txt (r/cursor state [:invite-emails :emails-txt])]
    (fn []
      (let [emails (txt->emails @emails-txt)
            email-count (count emails)
            unique-count (count (set emails))
            running? (action/running? :project/send-invites)]
        [:form.ui.form.bulk-invites-form
         {:on-submit (util/wrap-prevent-default
                       #(dispatch [:action [:project/send-invites project-id @emails-txt]]))}
         [:div.field
          [:textarea#bulk-invite-emails
           {:style {:width "100%"}
            :value @emails-txt
            :required true
            :rows 3
            :placeholder "Input a list of emails separated by comma, newlines or spaces."
            :on-change (util/wrap-prevent-default
                         #(reset! emails-txt (-> % .-target .-value)))}]]
         [Button {:primary true
                  :id "send-bulk-invites-button"
                  :disabled (or running? (zero? unique-count))
                  :type "submit"}
          "Send Invites"]
         (when (> email-count 0)
           [:span {:style {:margin-left "10px"}}
            (case email-count
              1 "1 email recognized"
              (str email-count " emails recognized"))
            (when (> email-count unique-count)
              (str " (" unique-count " unique)"))])]))))

(defn- InviteUsersBox []
  (let [invite-url @(subscribe [:project/invite-url])
        invite? (and invite-url (or @(subscribe [:self/member?])
                                    @(subscribe [:user/dev?])))]
    [:div.ui.segment
     (when invite?
       [:h4.ui.dividing.header 
        "Invite others to join"])
     (when invite?
       [:div.ui.fluid.action.input
        [:input#invite-url.ui.input {:readOnly true
                                     :value invite-url}]
        [ui/ClipboardButton "#invite-url" "Copy Invite Link"]])

     (when invite?
       [:h4.ui.dividing.header {:style {:margin-top "1.5em"}}
        "Send invitation emails"])
     (when invite?
       [InviteEmailsCmp])]))

(defn- UserModal [user-id member-info]
  (let [modal-state-path [:user-modal user-id]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id (subscribe [:active-project-id])
        gengroups (subscribe [:project/gengroups])
        current-gengroup (r/cursor state (concat modal-state-path [:user-modal :current-gengroup]))
        group-search-value (r/cursor state (concat modal-state-path [:user-modal :group-search-value]))
        add-member-to-gengroup (fn []
                                 (dispatch [:action [:project/add-member-to-gengroup
                                                     @project-id
                                                     (:gengroup-id @current-gengroup)
                                                     (:membership-id member-info)]])
                                 (reset! current-gengroup nil)
                                 (reset! group-search-value nil))]
    (fn [user-id member-info]
      (let [username @(subscribe [:user/display user-id])
            gengroup-ids-set (->> member-info :gengroups (map :gengroup-id) set)]
        [Modal {:trigger (r/as-element
                           [Button {:on-click #(dispatch [::set modal-state-path {:open true}])
                                    :data-member-username username
                                    :class "manage-member-btn icon"}
                            [:i.cog.icon]])
                :class "tiny"
                :open @modal-open
                :on-open #(reset! modal-open true)
                :on-close #(reset! modal-open false)}
         [ModalHeader
          [:span
           [Avatar {:user-id user-id}]
           username]]
         [ModalContent
          [ModalDescription
           [Form {:id "edit-user-modal"
                  :on-submit (fn [_e]
                               )}
            [:div.ui.field
             [:label "Groups"]
             [:div {:style {:margin-bottom "10px"}}
              (for [gengroup (:gengroups member-info)] ^{:key (:gengroup-id gengroup)}
                [:div.ui.teal.label
                 (:gengroup-name gengroup)
                 [:i.delete.icon
                  {:on-click #(dispatch [:action [:project/remove-member-from-gengroup
                                                  @project-id
                                                  (:gengroup-id gengroup)
                                                  (:membership-id member-info)]])}]])]]
            [Search
             {:id "search-gengroups-input"
              :placeholder "Search for groups"
              :minCharacters 0
              :on-result-select
              (fn [_e value]
                (let [{:keys [result]} (js->clj value :keywordize-keys true)
                      gengroup result]
                  (reset! current-gengroup gengroup)
                  (reset! group-search-value (:name gengroup))))
              :on-search-change
              (fn [_e value]
                (let [input-value (.-value value)]
                  (reset! group-search-value input-value)))
              :result-renderer
              (fn [item]
                (let [gengroup (js->clj item :keywordize-keys true)]
                  (r/as-element [:div {:key (:gengroup-id gengroup)
                                       :style {:display "flex"}}
                                 (:name gengroup)])))
              :results (->> (util/data-filter @gengroups [:name] @group-search-value)
                            (filter #(not (contains? gengroup-ids-set (:gengroup-id %))))
                            (map #(assoc % :title (:name %)))
                            (clj->js))
              :value (or @group-search-value "")
              :input (r/as-element
                       [Input {:placeholder "Search groups"
                               :action (r/as-element
                                         [Button {:id "add-gengroup-btn"
                                                  :positive true
                                                  :on-click #(add-member-to-gengroup)
                                                  :disabled (nil? @current-gengroup)}
                                          "Add"])}])}]
            
            [:div.ui.hidden.divider]
            [Button {:primary true
                     :style {:margin-top "10px"}
                     :on-click #(reset! modal-open false)}
             "OK"]]]]]))))

(defn- UserRow [user-id {:keys [permissions gengroups] :as member-info}]
  (let [max-gengroups-shown 2
        gengroups-count (count gengroups)
        username @(subscribe [:user/display user-id])
        admin? @(subscribe [:user/project-admin?])]
    [TableRow
     [TableCell
      (:username member-info)
      [Avatar {:user-id user-id}]
      [UserPublicProfileLink {:user-id user-id :display-name username}]]
     [TableCell
      [:div
       (doall
         (for [gengroup (take max-gengroups-shown gengroups)] ^{:key (:gengroup-id gengroup)}
           [:div.ui.small.teal.label (:gengroup-name gengroup)]))
       (when (> gengroups-count max-gengroups-shown)
         [:span.ui.small.grey.text
          (str " and " (- gengroups-count max-gengroups-shown) " more")])]]
     [TableCell
      (when admin?
        [:div
         [UserModal user-id member-info]])]]))

(defn- UsersTable []
  (let [offset (r/atom 0)
        members-search (r/atom "")
        items-per-page 10
        members (subscribe [::members/members])]
    (fn []
      (when @members
        (let [filtered-members (util/data-filter @members [#(-> % val :email)] @members-search)
              paginated-members (->> filtered-members (drop (* @offset items-per-page)) (take items-per-page))]
          [:div
           [:div {:style {:margin-bottom "10px"}}
            [:div.ui.fluid.left.icon.input
             [:input
              {:id "user-search"
               :type "text"
               ;; :value (or @input curval)
               :placeholder "Search users"
               :on-change (util/on-event-value #(reset! members-search %))}]
             [:i.search.icon]]]
           [:div
            [Table {:id "org-user-table" :basic true}
             [TableHeader
              [TableRow
               [TableHeaderCell "User"]
               ;; [TableHeaderCell "Permissions"]
               [TableHeaderCell {:class "wide"} "Groups"]
               [TableHeaderCell "Actions"]]]
             [TableBody
              (doall
                (for [[user-id member-info] paginated-members] ^{:key user-id}
                  [UserRow user-id member-info]))]]

            [:div.ui.segment
             [ListPager
              {:panel panel
               :instance-key [::members/members]
               :offset @offset
               :total-count (count filtered-members)
               :items-per-page items-per-page
               :item-name-string "members"
               :set-offset #(reset! offset %)}]]]])))))

(defn- UsersSegment []
  [:div.ui.segment
   [:h4.ui.dividing.header 
    "Project Members"]
   [UsersTable]])

(defn- NewGengroupModal []
  (let [modal-state-path [:gengroup-modal :new]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id @(subscribe [:active-project-id])
        gengroup-name (r/cursor state (concat modal-state-path [:form-data :name]))
        gengroup-description (r/cursor state (concat modal-state-path [:form-data :description]))]
    (fn []
      [Modal {:trigger (r/as-element
                         [Button {:on-click #(dispatch [::set modal-state-path {:open true
                                                                                :form-data {}}])
                                  :id "new-gengroup-btn"
                                  :class "ui small positive"}
                          [:i.icon.plus] " New"])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "New group"]
       [ModalContent
        [ModalDescription
         [Form {:on-submit (util/wrap-prevent-default
                             #(dispatch [:action [:project/create-member-gengroup project-id {:name @gengroup-name
                                                                                                :description @gengroup-description}]]))}
          [FormField
           [:label "Group name"]
           [Input {:id "gengroup-name-input"
                   :required true
                   :on-change (util/on-event-value #(reset! gengroup-name %))}]]
          [FormField
           [:label "Group description"]
           [TextArea {:label "Group name"
                      :id "gengroup-description-input"
                      :on-change (util/on-event-value #(reset! gengroup-description %))}]]
          [Button {:primary true
                   :id "create-gengroup-btn"}
           "Create"]]]]])))

(defn- GengroupModal [gengroup]
  (let [modal-state-path [:gengroup-modal (:gengroup-id gengroup)]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id @(subscribe [:active-project-id])
        gengroup-name (r/cursor state (concat modal-state-path [:form-data :name]))
        gengroup-description (r/cursor state (concat modal-state-path [:form-data :description]))]
    (fn [gengroup]
      [Modal {:trigger (r/as-element
                         [Button {:on-click #(dispatch [::set modal-state-path {:open true
                                                                                :form-data gengroup}])
                                  :data-gengroup-name (:name gengroup)
                                  :class "edit-gengroup-btn icon"}
                          [:i.icon.cog]])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Edit group"]
       [ModalContent
        [ModalDescription
         [Form {:on-submit (util/wrap-prevent-default
                             #(dispatch [:action [:project/update-member-gengroup project-id {:gengroup-id (:gengroup-id gengroup)
                                                                                              :name @gengroup-name
                                                                                              :description @gengroup-description}]]))}
          [FormField
           [:label "Group name"]
           [Input {:id "gengroup-name-input"
                   :default-value (:name gengroup)
                   :required true
                   :on-change (util/on-event-value #(reset! gengroup-name %))}]]
          [FormField
           [:label "Group description"]
           [TextArea {:label "Group name"
                      :id "gengroup-description-input"
                      :default-value (:description gengroup)
                      :on-change (util/on-event-value #(reset! gengroup-description %))}]]
          [Button {:primary true
                   :id "save-gengroup-btn"}
           "Save"]]]]])))

(defn- DeleteGengroupModal [gengroup]
  (let [modal-state-path [:delete-gengroup-modal (:gengroup-id gengroup)]
        project-id @(subscribe [:active-project-id])
        modal-open (r/cursor state (concat modal-state-path [:open]))]
    (fn [gengroup]
      [Modal {:trigger (r/as-element
                         [Button {:primary true
                                  :class "icon orange delete-gengroup-btn" 
                                  :data-gengroup-name (:name gengroup)
                                  :on-click #(dispatch [::set modal-state-path {:open true
                                                                                :form-data gengroup}])}
                          [:i.icon.trash]])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Delete group confirmation"]
       [ModalContent
        [ModalDescription
         [:p "Are you sure you want to delete group '" [:b (:name gengroup)]"'?"]
         [Button
          "Cancel"]
         [Button {:class "orange"
                  :on-click #(dispatch [:action [:project/delete-member-gengroup project-id (:gengroup-id gengroup)]])
                  :id "delete-gengroup-confirmation-btn"}
          "Yes, Delete"]]]])))

(defn- GengroupRow [gengroup]
  (let [admin? @(subscribe [:user/project-admin?])]
    [TableRow
     [TableCell
      [:span {:class "gengroup-name-cell"}
       (:name gengroup)]]
     [TableCell
      [:span.ui.text.grey {:class "gengroup-description-cell"}
       (:description gengroup)]]
     [TableCell
      [:div.ui.icon.buttons
      (when admin?
        [GengroupModal gengroup])
      " "
      (when admin?
        [DeleteGengroupModal gengroup])]]]))

(defn- GengroupsTable []
  (let [gengroups @(subscribe [:project/gengroups])]
    [:div
     [NewGengroupModal]
     
     [Table {:id "gengroups-table" :basic true}
      [TableHeader
       [TableRow
        [TableHeaderCell "Group"]
        [TableHeaderCell {:style {:width "100%"}} "Description"]
        [TableHeaderCell "Actions"]]]
      [TableBody
       (doall
         (for [gengroup gengroups] ^{:key (:gengroup-id gengroup)}
           [GengroupRow gengroup]))]]]))

(defn- GengroupsSegment []
  [:div.ui.segment
   [:h4.ui.dividing.header
    "Project Groups"]
   
   [GengroupsTable]])

(defn- ProjectOverviewContent []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project project-id]] {}
      [:div.overview-content
       [:div.ui.two.column.stackable.grid.project-overview
        [:div.column
         [UsersSegment]]
        [:div.column
         [InviteUsersBox]
         [GengroupsSegment]]]])))

(defn- Panel [child]
  (when-let [project-id @(subscribe [:active-project-id])]
    (if (false? @(subscribe [:project/has-articles?]))
      [:div (nav/nav (project-uri project-id "/add-articles") :redirect true)]
      [:div.project-content
       [ProjectOverviewContent]
       child])))

(def-panel :project? true :panel panel
  :uri "/users" :params [project-id] :name project
  :on-route (let [prev-panel @(subscribe [:active-panel])
                  all-items [[:project project-id]]]
              ;; avoid reloading data on project url redirect
              (when (and prev-panel (not= panel prev-panel))
                (doseq [item all-items] (dispatch [:reload item])))
              (when (not= panel prev-panel)
                ;; slight delay to reduce intermediate rendering during data load
                (js/setTimeout #(dispatch [:set-active-panel panel]) 20)))
  :content (fn [child] [Panel child]))
