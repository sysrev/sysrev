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
  :process (fn [_ [_] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[::set [:invite-emails :emails-txt] ""]
                             [:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/remove-from-member-gengroup
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id gengroup-id membership-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id
                :membership-id membership-id}))
  :process (fn [_ [_] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/add-from-member-gengroup
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id gengroup-id membership-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id
                :membership-id membership-id}))
  :process (fn [_ [_] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))


(def-action :project/create-member-gengroup
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id gengroup-id membership-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id
                :membership-id membership-id}))
  :process (fn [_ [_] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]]}))
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(def-action :project/save-member-gengroup
  :uri (fn [_ _] "/api/send-project-invites")
  :content (fn [project-id gengroup-id membership-id]
             (let []
               {:project-id project-id
                :gengroup-id gengroup-id
                :membership-id membership-id}))
  :process (fn [_ [_] {:keys [success message] :as result}]
             (if success
               {:dispatch-n [[:toast {:class "success" :message message}]]}))
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
  (let [modal-open      (r/atom false)
        search-value    (r/cursor state [:add-user :search-value])
        current-user-id (r/cursor state [:add-user :user-id])
        error           (r/cursor state [:add-user :error])
        
        ]
    (fn []
      (let [username @(subscribe [:user/display user-id])]
        [Modal {:trigger (r/as-element
                           [Button {:on-click #(dispatch [::set [:add-user] {:open true}])
                                    :class "icon"
                                    }
                            [:i.cog.icon]
                            ])
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
              (for [gengroup (:gengroups member-info)]
                [:div.ui.teal.label
                 (:gengroup-name gengroup)
                 [:i.delete.icon]])]]
            [Search
             {:id "org-search-users-input"
              :placeholder "Add user to group"
              :auto-focus true
              ;:loading (data/loading? :user-search)
              :on-result-select
              (fn [_e value]
                )
              :on-search-change
              (fn [_e value]
                )
              :result-renderer
              (fn [item]
                )
              :results []
              :value ""
              :input (r/as-element
                       [Input {:placeholder "Add to group"
                               :action (r/as-element
                                         [Button {:id "submit-add-member" :class "invite-member"
                                                  :positive true
                                                  :disabled (nil? @current-user-id)}
                                          "Add"])}])}]
            [Button {:primary true :style {:margin-top "10px"}}
             "Save"]]]]]))))

(defn- UserRow [user-id {:keys [permissions gengroups] :as member-info}]
  (let [self-id @(subscribe [:self/user-id])
        max-gengroups-shown 2
        gengroups-count (count gengroups)
        username @(subscribe [:user/display user-id])]
    [TableRow
     [TableCell
      [Avatar {:user-id user-id}]
      [UserPublicProfileLink {:user-id user-id :display-name username}]]
     ;; [TableCell
     ;;  [:div
     ;;   (for [permission permissions]
     ;;     [:div.ui.small.label permission]) ]]
     [TableCell
      [:div
       (doall
         (for [gengroup (take max-gengroups-shown gengroups)]
           [:div.ui.small.teal.label (:gengroup-name gengroup)]))
       (if (> gengroups-count max-gengroups-shown)
         [:span.ui.small.grey.text
          (str "and " (- gengroups-count max-gengroups-shown) " more")])]]
     [TableCell
      (when true ; TODO: Check admin
        [UserModal user-id member-info])]]))

(defn UserSearchInput [context]
  (let [;input (subscribe [::inputs context [:text-search]])
        ;set-input #(dispatch-sync [::al/set context [:inputs :text-search] %])
        ;curval @(subscribe [::al/get context [:text-search]])
        ;synced? (or (nil? @input) (= @input curval))

        ]
    [:div.ui.fluid.left.icon.input
    ; {:class (css [(not synced?) "loading"])}
     [:input
      {:id "user-search"
       :type "text"
       ;; :value (or @input curval)
       :placeholder "Search users"
       :on-change println #_(util/on-event-value
                   #(do (set-input %)
                        (-> (fn [] (let [value (not-empty %)
                                         later-value (if (empty? @input) nil @input)]
                                     (when (= value later-value)
                                       (dispatch-sync [::set-text-search context value]))))
                            (js/setTimeout 1000))))}]
     [:i.search.icon]]))

(defn- UsersTable [org-id]
  (when-let [members @(subscribe [::members/members]) #_[
                        {:user-id 3452, :permissions ["member" "admin"], :primary-email-verified false, :date-created #inst "2020-11-18T21:57:15.437-00:00", :username "m", :introduction nil}
                        {:user-id 3453, :permissions ["member"], :primary-email-verified false, :date-created #inst "2020-11-18T21:59:54.228-00:00", :username "mardodcp", :groups ["Spanish"] :introduction nil}
                        {:user-id 3453, :permissions ["member"], :primary-email-verified false, :date-created #inst "2020-11-18T21:59:54.228-00:00", :username "mardo", :groups ["Spanish" "Japanese" "English"] :introduction nil}
                        
                        ] #_(not-empty @(subscribe [:org/users org-id]))]
    [:div
     [UserSearchInput]
     [Table {:id "org-user-table" :basic true}
      [TableHeader
       [TableRow
        [TableHeaderCell "User"]
        ;; [TableHeaderCell "Permissions"]
        [TableHeaderCell {:class "wide"} "Groups"]
        [TableHeaderCell "Actions"]]]
      [TableBody
       (doall
         (for [[user-id member-info] members] ^{:key user-id}
           [UserRow user-id member-info]))]]
     
     #_[:div.ui.segments
      
      [:div.ui.segment
       [ListPager
        {:panel nil
         :instance-key [:article-list]
         ;; :offset @(subscribe [::al/display-offset context])
         :total-count 3
         :items-per-page 10
         :item-name-string "users"
         :set-offset #(do )
         :on-nav-action (fn [action _offset]
                          )}]]]]))

(defn- UsersSegment []
  [:div.ui.segment
   [:h4.ui.dividing.header 
    "Project Members"]
   [UsersTable]])

(defn- GroupModal [group]
  (let [modal-open      (r/atom false)
        search-value    (r/cursor state [:add-user :search-value])
        current-user-id (r/cursor state [:add-user :user-id])
        error           (r/cursor state [:add-user :error])]
    (fn []
      [Modal {:trigger (r/as-element
                         [Button {:on-click #(dispatch [::set [:add-user] {:open true}])
                                  :class "icon"
                                  }
                          [:i.cog.icon]
                          ])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Edit group"]
       [ModalContent
        [ModalDescription
         [Form {:id "invite-member-form"
                :on-submit (fn [_e]
                             )}
          [FormField
           [:label "Group name"]
           [Input {:id "group-name-input"
                   :default-value ""
                   :value (:name group)
                   :on-change (util/on-event-value
                                #(do ))}]]
          [FormField
           [:label "Group description"]
           [TextArea {:label "Group name"
                      :id "group-description-input"
                      :default-value ""
                      :value (:description group)
                      :on-change (util/on-event-value
                                   #(do ))}]]
          [Button {:primary true}
           "Save"]
          ]]]])))

(defn- GroupRow [group org-id]
  (let [self-id @(subscribe [:self/user-id])]
    [TableRow
     [TableCell
      (:name group)]
     
     [TableCell
      [:span.ui.text.grey (:description group)]]
     [TableCell
      (when true ; TODO: Check permissions
        [GroupModal group])]]))

(defn GroupSearchInput [context]
  (let [input (subscribe [::inputs context [:text-search]])
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

(defn- GroupsTable [org-id]
  (when-let [groups @(subscribe [:project/gengroups]) #_[
                     {:id 3 :description "English speaking members" :name "English"}
                     {:id 1 :name "Japanese" :description "Japanese speaking members"}
                     {:id 2 :description "Spanish speaking members" :name "Spanish"}
                     
                     ] #_(not-empty @(subscribe [:org/users org-id]))]
    [:div
     [GroupSearchInput]
     [Table {:id "groups-table" :basic true}
      [TableHeader
       [TableRow
        [TableHeaderCell "Group"]
        [TableHeaderCell {:style {:width "100%"}} "Description"]
        [TableHeaderCell "Actions"]]]
      [TableBody
       (doall
         (for [group groups] ^{:key (:group-id group)}
           [GroupRow group org-id]))]]]))

(defn- GroupsSegment []
  [:div.ui.segment
   [:h4.ui.dividing.header
    "Project Groups"]
   [GroupsTable]])

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
         [GroupsSegment]]]])))

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
                  all-items [[:project project-id]
                             [:project/review-status project-id]
                             [:project/markdown-description project-id {:panel panel}]
                             [:project/label-counts project-id]
                             [:project/important-terms-text project-id]
                             [:project/prediction-histograms project-id]]]
              ;; avoid reloading data on project url redirect
              (when (and prev-panel (not= panel prev-panel))
                (doseq [item all-items] (dispatch [:reload item])))
              (when (not= panel prev-panel)
                ;; slight delay to reduce intermediate rendering during data load
                (js/setTimeout #(dispatch [:set-active-panel panel]) 20)))
  :content (fn [child] [Panel child]))
