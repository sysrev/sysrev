(ns sysrev.views.panels.org.users
  (:require [ajax.core :refer [GET POST PUT DELETE]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-sub dispatch reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.semantic :refer [Segment Table TableHeader TableBody TableRow TableCell Search SearchResults Button
                                           Modal ModalHeader ModalContent ModalDescription Form FormGroup Checkbox
                                           Input Message MessageHeader Dropdown Menu Icon]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def ^:private panel [:org :users])

(def state (r/cursor app-db [:state :panels panel]))

(reg-sub :org/users
         (fn [db [event org-id]]
           (get-in db [:org org-id :users])))

(reg-event-db
 :org/set-users!
 [trim-v]
 (fn [db [org-id users]]
   (assoc-in db [:org org-id :users] users)))

(defn get-org-users!
  [org-id]
  (let [retrieving? (r/cursor state [:retrieving-org-users?])
        error (r/cursor state [:retrieving-org-users-error])]
    (reset! retrieving? true)
    (GET (str "/api/org/" org-id "/users")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving? false)
                     (dispatch [:org/set-users! org-id (get-in response [:result :users])]))
          :error-handler (fn [error-response]
                           (reset! retrieving? false)
                           (reset! error (get-in error-response [:response :error :messaage])))})))

(reg-event-fx :org/get-users! (fn [_ [_ org-id]]
                                (get-org-users! org-id)
                                {}))

(defn remove-from-org!
  [{:keys [user-id org-id]}]
  (let [retrieving? (r/cursor state [:remove-from-org! :retrieving])
        error (r/cursor state [:remove-from-org! :error])
        modal-open (r/cursor state [:remove-modal :open])]
    (reset! retrieving? true)
    (reset! error "")
    (DELETE (str "/api/org/" org-id "/user")
            {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
             :params {:user-id user-id}
             :handler (fn [response]
                        (reset! retrieving? false)
                        (reset! error "")
                        (dispatch [:org/get-users! org-id])
                        (reset! modal-open false))
             :error-handler (fn [response]
                              (reset! retrieving? false)
                              (reset! error (get-in response [:response :error :message])))})))

(defn RemoveModal
  [{:keys [org-id]}]
  (let [modal-open (r/cursor state [:remove-modal :open])
        user-id (r/cursor state [:current-user-id])
        username (r/cursor state [:current-username])
        error (r/cursor state [:remove-from-org! :error])
        retrieving? (r/cursor state [:remove-from-org! :retrieving])]
    [Modal {:open @modal-open
            :on-open #(reset! modal-open true)
            :on-close #(reset! modal-open false)
            :close-icon true}
     [ModalHeader (str "Removing 1 member from " @(subscribe [:orgs/org-name org-id]))]
     [ModalContent
      [ModalDescription
       [Form {:on-submit #(remove-from-org! {:user-id @user-id
                                             :org-id org-id})}
        [:h4
         "The following members will be removed: "]
        [Table {:basic true
                :style {:width "50%"}}
         [TableBody
          [TableRow
           [TableCell
            [Avatar {:user-id @user-id}]
            [UserPublicProfileLink {:user-id @user-id :display-name @username}]]]]]
        [Button {:color "red"
                 :basic true
                 :disabled @retrieving?}
         "Remove members"]
        (when-not (empty? @error)
          [Message {:negative true
                    :onDismiss #(reset! error "")}
           [MessageHeader {:as "h4"} "Remove member error"]
           @error])]]]]))

(defn change-role!
  [{:keys [new-role user-id permissions org-id]}]
  (let [retrieving? (r/cursor state [:change-role! :retrieving])
        error (r/cursor state [:change-role! :error])
        modal-open (r/cursor state [:change-role-modal :open])]
    (reset! retrieving? true)
    (reset! error "")
    (PUT (str "/api/org/" org-id "/user")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :params {:user-id user-id
                   :permissions permissions}
          :handler (fn [response]
                     (reset! retrieving? false)
                     (reset! error "")
                     (dispatch [:org/get-users! org-id])
                     (reset! modal-open false))
          :error-handler (fn [response]
                           (reset! retrieving? false)
                           (reset! error (get-in response [:response :error :message])))})))

(defn ChangeRoleModal
  [{:keys [org-id]}]
  (let [modal-open (r/cursor state [:change-role-modal :open])
        user-id (r/cursor state [:current-user-id])
        username (r/cursor state [:current-username])
        new-role (r/cursor state [:change-role-modal :new-role])
        error (r/cursor state [:change-role! :error])
        retrieving? (r/cursor state [:change-role! :retrieving])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Modal {:open @modal-open
                :on-open #(reset! modal-open true)
                :on-close #(reset! modal-open false)
                :close-icon true}
         [ModalHeader (str "Change the role of " @username "?")]
         [ModalContent
          [ModalDescription
           [Form {:on-submit #(change-role! {:new-role @new-role
                                             :user-id @user-id
                                             :permissions [@new-role]
                                             :org-id org-id})}
            [:h4 {:style {:margin-left "-0.5rem"}} "Select a new role"]
            [FormGroup
             [Checkbox {:label "Owner"
                        :as "h4"
                        :checked (= @new-role "owner")
                        :on-change #(reset! new-role "owner")
                        :radio true
                        :style {:display "block"}}]]
            [:p {:style {:margin-top "0px"
                         :margin-left "1.5rem"}} "Has full administrative access to the entire organization. Can add, remove, and edit users and projects"]
            [FormGroup
             [Checkbox {:label "Member"
                        :as "h4"
                        :checked (= @new-role "member")
                        :on-change #(reset! new-role "member")
                        :radio true}]]
            [:p {:style {:margin-top "0px"
                         :margin-left "1.5rem"}} "Can see every member and project in the organization"]
            [Button {:disabled (or (nil? @new-role)
                                   @retrieving?)
                     :id "org-change-role-button"
                     :color "red"
                     :basic true}
             "Change Role"]
            (when-not (empty? @error)
              [Message {:negative true
                        :onDismiss #(reset! error "")}
               [MessageHeader {:as "h4"} "Change role error"]
               @error])]]]])
      :component-did-mount (fn [this]
                             (reset! new-role nil)
                             (reset! error ""))})))

(defn UserRow
  [{:keys [user-id username permissions]} org-id]
  (let [change-role-modal-open (r/cursor state [:change-role-modal :open])
        remove-modal-open (r/cursor state [:remove-modal :open])
        current-user-id (r/cursor state [:current-user-id])
        current-username (r/cursor state [:current-username])
        self-user-id @(subscribe [:self/user-id])
        self-permissions @(subscribe [:orgs/org-permissions org-id])]
    [TableRow
     [TableCell
      [Avatar {:user-id user-id}]
      [UserPublicProfileLink {:user-id user-id :display-name username}]]
     [TableCell
      [:p (some #{"admin" "owner" "member"} permissions)]]
     [TableCell
      (when (and
             ;; only admins and owners can change group permissions
             (some #{"admin" "owner"}
                   self-permissions)
             ;; don't allow changing of perms when self is the owner
             (not (and (= self-user-id user-id)
                       (some #{"owner"} self-permissions))))
        [Dropdown {:button true
                   :search true
                   :class-name "icon"
                   :icon "cog"
                   :text " "
                   :select-on-blur false
                   :options [{:text "Change role..."
                              :value "change-role"
                              :key :change-role}
                             {:text "Remove from organization..."
                              :value "remove-from-org"
                              :key :remove-from-org}]
                   :on-change (fn [event data]
                                (let [value ($ data :value)]
                                  (reset! current-user-id user-id)
                                  (reset! current-username username)
                                  (condp = value
                                    "change-role"
                                    (do
                                      (reset! (r/cursor state [:change-role! :error]) "")
                                      (reset! (r/cursor state [:change-role-modal :new-role]) nil)
                                      (reset! change-role-modal-open true))
                                    "remove-from-org"
                                    (do (reset! (r/cursor state [:remove-from-org! :error]) "")
                                        (reset! remove-modal-open true)))))}])]]))

(defn UsersTable
  [{:keys [org-users org-id]}]
  (when-not (empty? org-users)
    [Table {:basic "true"
            :id "org-user-table"}
     #_[TableHeader
      [TableRow
       [TableCell ;; select all goes here
        ]
       [TableCell ;; filter by row goes herev
        ]]]
     [TableBody
      (map (fn [user]
             ^{:key (:user-id user)}
             [UserRow user org-id])
           org-users)]]))

(defn user-suggestions!
  [term]
  (let [retrieving? (r/cursor state [:search-loading?])
        user-search-results (r/cursor state [:user-search-results])
        org-users (r/cursor state [:org-users])
        org-users-set (->> @org-users
                           (map #(dissoc % :primary-email-verified))
                           set)]
    (when-not (empty? term)
      (reset! retrieving? true)
      (reset! user-search-results [])
      (GET "/api/users/search"
           {:params {:term term}
            :handler (fn [response]
                       (reset! retrieving? false)
                       ;; need to add a key value for the render-results fn of the
                       ;; search component
                       (reset! user-search-results (map #(assoc % :key (:user-id %))
                                                        (-> (get-in response [:result :users])
                                                            set
                                                            (clojure.set/difference org-users-set)))))
            :error-handler (fn [response]
                             (reset! retrieving? false)
                             ($ js/console log "[sysrev.views.panels.org.users/user-suggestions] Error retrieving search results"))}))))

(defn add-user-to-group!
  [user-id org-id]
  (let [retrieving? (r/cursor state [:posting-add-user-to-group])
        error (r/cursor state [:posting-add-user-to-group-error])
        modal-open (r/cursor state [:invite-member-modal-open?])]
    (reset! retrieving? true)
    (reset! error "")
    (POST (str "/api/org/" org-id "/user")
          {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :params {:user-id user-id}
           :handler (fn [response]
                      (reset! retrieving? false)
                      (reset! error "")
                      (dispatch [:org/get-users! org-id])
                      (reset! modal-open false))
           :error-handler (fn [response]
                            (reset! retrieving? false)
                            (reset! error (get-in response [:response :error :messaage])))})))

(defn InviteMemberModal
  [{:keys [org-id]}]
  (let [modal-open (r/cursor state [:invite-member-modal-open?])
        search-loading? (r/cursor state [:search-loading?])
        user-search-results (r/cursor state [:user-search-results])
        user-search-value (r/cursor state [:user-search-value])
        current-search-user-id (r/cursor state [:current-search-user-id])
        error (r/cursor state [:posting-add-user-to-group-error])
        reset-state! (fn []
                       (reset! user-search-results nil)
                       (reset! user-search-value "")
                       (reset! current-search-user-id nil)
                       (reset! error nil))
        set-current-search-user-id! (fn []
                                      (reset! current-search-user-id
                                              (->> @user-search-results
                                                   (filter #(= (:username %) @user-search-value))
                                                   first
                                                   :user-id)))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Modal {:trigger (r/as-component [Button {:id "add-member-button"
                                                  :on-click #(reset-state!)
                                                  :positive true}
                                          "Add Member"])
                :open @modal-open
                :on-open #(reset! modal-open true)
                :on-close #(reset! modal-open false)
                :close-icon true}
         [ModalHeader (str "Invite Member to " org-id)]
         [ModalContent
          [ModalDescription
           [Form {:id "invite-member-form"
                  :on-submit (fn [event]
                               (set-current-search-user-id!)
                               (when-not (nil? @current-search-user-id)
                                 (add-user-to-group! @current-search-user-id org-id)))}
            [:div
             [Search {:loading @search-loading?
                      :placeholder "Search for users by username"
                      :id "org-search-users-input"
                      :on-result-select (fn [e value]
                                          (let [result (-> value
                                                           (js->clj :keywordize-keys true)
                                                           :result)]
                                            (reset! current-search-user-id (:user-id result))
                                            (reset! user-search-value (:username result))))
                      :on-search-change (fn [e value]
                                          (let [input-value (-> value
                                                                (js->clj :keywordize-keys true)
                                                                :value)]
                                            (reset! user-search-value input-value)
                                            (set-current-search-user-id!)
                                            (user-suggestions! input-value)))
                      :result-renderer (fn [item]
                                         (let [item (js->clj item :keywordize-keys true)]
                                           (r/as-component
                                            [:div {:style {:display "flex"}}
                                             [Avatar {:user-id (:user-id item)}]
                                             [:p (:username item)]])))
                      :results @user-search-results
                      :value @user-search-value
                      :input (r/as-element
                              [Input {:placeholder "Search for users by username"
                                      :action (r/as-element
                                               [Button {:id "submit-add-member"
                                                        :class "invite-member"
                                                        :positive true
                                                        :disabled (nil? @current-search-user-id)}
                                                "Add Member"])}])}]]
            (when-not (empty? @error)
              [Message {:negative true
                        :onDismiss #(reset! error "")}
               [MessageHeader {:as "h4"} "Add Member Error"]
               @error])]]]])
      :get-initial-state (fn [this]
                           (reset-state!)
                           {})
      :component-did-mount (fn [this]
                             (reset-state!))})))

(defn OrgUsers [{:keys [org-id]}]
  (let [org-users (subscribe [:org/users org-id])
        org-permissions (subscribe [:orgs/org-permissions org-id])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (dispatch [:org/get-users! org-id])
        [:div
         (when (some #{"owner" "admin"} @org-permissions)
           [InviteMemberModal {:org-id org-id}])
         [ChangeRoleModal {:org-id org-id}]
         [RemoveModal {:org-id org-id}]
         [UsersTable {:org-users @org-users
                      :org-id org-id}]])})))

