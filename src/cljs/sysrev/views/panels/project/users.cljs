(ns sysrev.views.panels.project.users
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [sysrev.chartjs :as chartjs]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.components.core :as ui]
            [sysrev.state.project.members :as members]
            [sysrev.views.charts :as charts]
            [sysrev.views.semantic :as S :refer
             [Table TableBody TableRow TableHeader TableHeaderCell TableCell Search Button
              Modal ModalHeader ModalContent ModalDescription Form FormGroup Checkbox
              Input FormField TextArea Label]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.util :as util :refer [css wrap-user-event]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :users]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(defn txt->emails [txt]
  (if (string? txt)
    (->> (str/split txt #"[ ,\n]")
         (map str/trim)
         (filter util/email?))))

(def-action :project/send-invites
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id emails-txt]
             (let [emails (txt->emails emails-txt)]
               {:project-id project-id
                :emails emails}))
  :process (fn [_ [project-id emails-txt] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[::set [:invite-emails :emails-txt] ""]
                             [:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/create-member-gengroup
  :uri (fn [_ _] "/api/create-gengroup")
  :content (fn [project-id gengroup]
             (let []
               {:project-id project-id
                :gengroup-name (:name gengroup)
                :gengroup-description (:description gengroup)}))
  :process (fn [{:keys [db]} [project-id gengroup] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[::set [:gengroup-modal :new :open] false]
                             [:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/update-member-gengroup
  :uri (fn [_ _] "/api/update-gengroup")
  :content (fn [project-id gengroup]
             (let []
               {:project-id project-id
                :gengroup-id (:gengroup-id gengroup)
                :gengroup-name (:name gengroup)
                :gengroup-description (:description gengroup)}))
  :process (fn [{:keys [db]} [project-id gengroup] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[::set [:gengroup-modal (:gengroup-id gengroup) :open] false]
                             [:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/delete-member-gengroup
  :uri (fn [_ _] "/api/delete-gengroup")
  :content (fn [project-id gengroup-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id}))
  :process (fn [{:keys [db]} [project-id gengroup-id] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/add-member-to-gengroup
  :uri (fn [_ _] "/api/add-member-to-gengroup")
  :content (fn [project-id gengroup-id membership-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id
                :membership-id membership-id}))
  :process (fn [_ [project-id gengroup-id membership-id] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/remove-member-from-gengroup
  :uri (fn [_ _] "/api/remove-member-from-gengroup")
  :content (fn [project-id gengroup-id membership-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id
                :membership-id membership-id}))
  :process (fn [_ [project-id gengroup-id membership-id] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]
                             [:reload [:project project-id]]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
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
         (if (> email-count 0)
           [:span {:style {:margin-left "10px"}}
            (case email-count
              1 "1 email recognized"
              (str email-count " emails recognized"))
            (if (> email-count unique-count)
              (str " (" unique-count " unique)"))])]))))

(defn- InviteUsersBox []
  (let [project-id @(subscribe [:active-project-id])
        visible-user-ids (->> @(subscribe [:project/member-user-ids])
                              (sort-by #(deref (subscribe [:member/article-count %])) >))
        user-names (->> visible-user-ids
                        (mapv #(deref (subscribe [:user/display %]))))
        includes   (->> visible-user-ids
                        (mapv #(deref (subscribe [:member/include-count %]))))
        excludes   (->> visible-user-ids
                        (mapv #(deref (subscribe [:member/exclude-count %]))))
        yss [includes excludes]
        ynames ["Include" "Exclude"]
        invite-url @(subscribe [:project/invite-url])
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

     (if invite?
       [:h4.ui.dividing.header {:style {:margin-top "1.5em"}}
        "Send invitation emails"])
     (if invite?
       [InviteEmailsCmp])]
    ))

(defn- UserModal [user-id member-info]
  (let [modal-state-path [:gengroup-modal user-id]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id (subscribe [:active-project-id])
        gengroups (subscribe [:project/gengroups])
        current-gengroup (r/cursor state (concat modal-state-path [:user-modal :current-gengroup]))
        group-search-value (r/cursor state (concat modal-state-path [:user-modal :group-search-value]))
        error (r/cursor state (concat modal-state-path [:user-modal :error]))
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
                                    :class "icon"}
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
             {:id "search-groups-input"
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
                                         [Button {:id "submit-add-member" :class "invite-member"
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
  (let [self-id @(subscribe [:self/user-id])
        max-gengroups-shown 2
        gengroups-count (count gengroups)
        username @(subscribe [:user/display user-id])
        admin? @(subscribe [:user/project-admin?])]
    [TableRow
     [TableCell
      (:username member-info)
      [Avatar {:user-id user-id}]
      [UserPublicProfileLink {:user-id user-id :display-name username}]]
     ;; [TableCell
     ;;  [:div
     ;;   (for [permission permissions]
     ;;     [:div.ui.small.label permission]) ]]
     [TableCell
      [:div
       (doall
         (for [gengroup (take max-gengroups-shown gengroups)] ^{:key (:gengroup-id gengroup)}
           [:div.ui.small.teal.label (:gengroup-name gengroup)]))
       (if (> gengroups-count max-gengroups-shown)
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
      (if @members
        (let [filtered-members (util/data-filter @members [#(-> % val :email)] @members-search)
              paginated-members (->> filtered-members (drop (* @offset items-per-page)) (take items-per-page))]
          [:div
           [:div {:style {:margin-bottom "10px"}}
            [:div.ui.fluid.left.icon.input
             ; {:class (css [(not synced?) "loading"])}
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
        gengroup-description (r/cursor state (concat modal-state-path [:form-data :description]))
        error (r/cursor state (concat modal-state-path [:form-error]))]
    (fn [gengroup icon]
      [Modal {:trigger (r/as-element
                         [Button {:on-click #(dispatch [::set modal-state-path {:open true
                                                                                :form-data {}}])
                                  :class "ui small positive"}
                          [:i.icon.plus] " New"])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "New group"]
       [ModalContent
        [ModalDescription
         [Form {:id "invite-member-form"
                :on-submit (util/wrap-prevent-default
                             #(dispatch [:action [:project/create-member-gengroup project-id {:name @gengroup-name
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
          [Button {:primary true}
           "Save"]]]]])))

(defn- GengroupModal [gengroup]
  (let [modal-state-path [:gengroup-modal (:gengroup-id gengroup)]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id @(subscribe [:active-project-id])
        gengroup-name (r/cursor state (concat modal-state-path [:form-data :name]))
        gengroup-description (r/cursor state (concat modal-state-path [:form-data :description]))
        error (r/cursor state (concat modal-state-path [:form-error]))]
    (fn [gengroup]
      [Modal {:trigger (r/as-element
                         [Button {:on-click #(dispatch [::set modal-state-path {:open true
                                                                                :form-data gengroup}])
                                  :class "icon"}
                          [:i.icon.cog]])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Edit group"]
       [ModalContent
        [ModalDescription
         [Form {:id "invite-member-form"
                :on-submit (util/wrap-prevent-default
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
          [Button {:primary true}
           "Save"]]]]])))

(defn- GengroupDeleteButton [gengroup]
  (let [project-id @(subscribe [:active-project-id])]
    [Button {:primary true
             :class "icon orange" 
             :on-click (fn []
                         (if (js/confirm "Are you sure you want to delete this group?")
                           (dispatch [:action [:project/delete-member-gengroup project-id (:gengroup-id gengroup)]])))}
     [:i.icon.trash]]))

(defn- GengroupRow [gengroup org-id]
  (let [self-id @(subscribe [:self/user-id])
        admin? @(subscribe [:user/project-admin?])]
    [TableRow
     [TableCell
      (:name gengroup)]
     
     [TableCell
      [:span.ui.text.grey (:description gengroup)]]
     [TableCell
      [:div.ui.icon.buttons
      (when admin?
        [GengroupModal gengroup])
      " "
      (when admin?
        [GengroupDeleteButton gengroup])]]]))

(defn- GengroupSearchInput [context]
  (let [;input (subscribe [::inputs context [:text-search]])
        ;set-input #(dispatch-sync [::al/set context [:inputs :text-search] %])
        ;curval @(subscribe [::al/get context [:text-search]])
        ;synced? (or (nil? @input) (= @input curval))
        ]
    [:div.ui.fluid.left.icon.input
    ; {:class (css [(not synced?) "loading"])}
     [:input
      {:id "group-search"
       :type "text"
       ;; :value (or @input curval)
       :placeholder "Search groups"
       :on-change println #_(util/on-event-value
                   #(do (set-input %)
                        (-> (fn [] (let [value (not-empty %)
                                         later-value (if (empty? @input) nil @input)]
                                     (when (= value later-value)
                                       (dispatch-sync [::set-text-search context value]))))
                            (js/setTimeout 1000))))}]
     [:i.search.icon]]))

(defn- GengroupsTable [org-id]
  (when-let [gengroups @(subscribe [:project/gengroups])]
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
           [GengroupRow gengroup org-id]))]]]))

(defn- GengroupsSegment []
  [:div.ui.segment
   [:h4.ui.dividing.header
    "Project Groups"]
   
   [GengroupsTable]])

(defn- ProjectOverviewContent []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project project-id]
                  [:project/markdown-description project-id {:panel panel}]] {}
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
