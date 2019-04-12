(ns sysrev.views.panels.org.users
  (:require [ajax.core :refer [GET POST PUT DELETE]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.semantic :refer [Segment Table TableHeader TableBody TableRow TableCell Search SearchResults Button
                                           Modal ModalHeader ModalContent ModalDescription Form FormGroup Checkbox
                                           Input Message MessageHeader Dropdown Menu Icon]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def ^:private panel [:org :users])

(def state (r/cursor app-db [:state :panels panel]))

(defn get-user-id-permissions
  [user-id]
  (let [org-users (r/cursor state [:org-users])]
    (->> @org-users
         (filter #(= (:user-id %)
                     user-id))
         first
         :permissions)))

(defn get-org-users!
  []
  (let [org-users (r/cursor state [:org-users])
        retrieving? (r/cursor state [:retrieving-org-users?])
        error (r/cursor state [:retrieving-org-users-error])]
    (reset! retrieving? true)
    (GET (str "/api/org/" @(subscribe [:current-org]) "/users")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving? false)
                     (reset! org-users (get-in response [:result :users])))
          :error-handler (fn [error-response]
                           (reset! retrieving? false)
                           (reset! error (get-in error-response [:response :error :messaage])))})))

(defn remove-from-org!
  [{:keys [user-id]}]
  (let [retrieving? (r/cursor state [:remove-from-org! :retrieving])
        error (r/cursor state [:remove-from-org! :error])
        modal-open (r/cursor state [:remove-modal :open])]
    (reset! retrieving? true)
    (reset! error "")
    (DELETE (str "/api/org/" @(subscribe [:current-org]) "/user")
            {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
             :params {:user-id user-id}
             :handler (fn [response]
                        (reset! retrieving? false)
                        (reset! error "")
                        (get-org-users!)
                        (reset! modal-open false))
             :error-handler (fn [response]
                              (reset! retrieving? false)
                              (reset! error (get-in response [:response :error :message])))})))

(defn RemoveModal
  []
  (let [modal-open (r/cursor state [:remove-modal :open])
        user-id (r/cursor state [:current-user-id])
        username (r/cursor state [:current-username])
        error (r/cursor state [:remove-from-org! :error])
        retrieving? (r/cursor state [:remove-from-org! :retrieving])]
    [Modal {:open @modal-open
            :on-open #(reset! modal-open true)
            :on-close #(reset! modal-open false)
            :close-icon true}
     [ModalHeader (str "Removing 1 member from " @(subscribe [:current-org-name]))]
     [ModalContent
      [ModalDescription
       [Form {:on-submit #(remove-from-org! {:user-id @user-id})}
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
  [{:keys [new-role user-id permissions]}]
  (let [retrieving? (r/cursor state [:change-role! :retrieving])
        error (r/cursor state [:change-role! :error])
        modal-open (r/cursor state [:change-role-modal :open])]
    (reset! retrieving? true)
    (reset! error "")
    (PUT (str "/api/org/" @(subscribe [:current-org]) "/user")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :params {:user-id user-id
                   :permissions permissions}
          :handler (fn [response]
                     (reset! retrieving? false)
                     (reset! error "")
                     (get-org-users!)
                     (reset! modal-open false))
          :error-handler (fn [response]
                           (reset! retrieving? false)
                           (reset! error (get-in response [:response :error :message])))})))

(defn ChangeRoleModal
  []
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
                                             :permissions [@new-role]})}
            [:h4 {:style {:margin-left "-0.5rem"}} "Select a new role"]
            [FormGroup
             [Checkbox {:label "Owner"
                        :as "h4"
                        :checked (= @new-role "owner")
                        :on-change #(reset! new-role "owner")
                        :radio true
                        :style {:display "block"}}]]
            [:p {:style {:margin-top "0px"
                         :margin-left "1.5rem"}} "Has full administrative access to the entire organization."]
            [FormGroup
             [Checkbox {:label "Member"
                        :as "h4"
                        :checked (= @new-role "member")
                        :on-change #(reset! new-role "member")
                        :radio true}]]
            [:p {:style {:margin-top "0px"
                         :margin-left "1.5rem"}} "Can see every member in the organization, and can create new projects."]
            [Button {:disabled (or (nil? @new-role)
                                   @retrieving?)
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
  [{:keys [user-id username permissions]}]
  (let [change-role-modal-open (r/cursor state [:change-role-modal :open])
        remove-modal-open (r/cursor state [:remove-modal :open])
        current-user-id (r/cursor state [:current-user-id])
        current-username (r/cursor state [:current-username])
        self-user-id @(subscribe [:self/user-id])
        self-permissions (get-user-id-permissions self-user-id)]
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
                   ;;:pointing true
                   :search true
                   :pointing "down"
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
  [users]
  (when-not (empty? @users)
    [Table {:basic "true"}
     #_[TableHeader
      [TableRow
       [TableCell ;; select all goes here
        ]
       [TableCell ;; filter by row goes herev
        ]]]
     [TableBody
      (map (fn [user]
             ^{:key (:user-id user)}
             [UserRow user])
           @users)]]))

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
                      (get-org-users!)
                      (reset! modal-open false))
           :error-handler (fn [response]
                            (reset! retrieving? false)
                            (reset! error (get-in response [:response :error :messaage])))})))

(defn InviteMemberModal
  []
  (let [modal-open (r/cursor state [:invite-member-modal-open?])
        search-loading? (r/cursor state [:search-loading?])
        user-search-results (r/cursor state [:user-search-results])
        user-search-value (r/cursor state [:user-search-value])
        current-search-user-id (r/cursor state [:current-search-user-id])
        error (r/cursor state [:posting-add-user-to-group-error])
        current-org-name (subscribe [:current-org-name])
        current-org-id (subscribe [:current-org])
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
        [Modal {:trigger
                (r/as-component [Button {:on-click (fn [event]
                                                     (reset-state!))
                                         :positive true} "Add Member"])
                :open @modal-open
                :on-open #(reset! modal-open true)
                :on-close #(reset! modal-open false)
                :close-icon true}
         [ModalHeader (str "Invite Member to " @current-org-name)]
         [ModalContent
          [ModalDescription
           [Form {:on-submit (fn [event]
                               (set-current-search-user-id!)
                               (when-not (nil? @current-search-user-id)
                                 (add-user-to-group! @current-search-user-id @current-org-id)))}
            [:div
             [Search {:loading @search-loading?
                      :placeholder "Search for users by username"
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
                                             [Avatar {:user-id (:user-id item)}] [:p (:username item)]])))
                      :results @user-search-results
                      :value @user-search-value
                      :input (r/as-element
                              [Input {:placeholder "Search for users by username"
                                      :action (r/as-element [Button {:positive true
                                                                     :class "invite-member"
                                                                     :disabled (nil? @current-search-user-id)}
                                                             "Add Member"])}])}]]
            (when-not (empty? @error)
              [Message {:negative true
                        :onDismiss #(reset! error "")}
               [MessageHeader {:as "h4"} "Add Member Error"]
               @error])]]]])
      :get-initial-state
      (fn [this]
        (reset-state!)
        {})
      :component-did-mount
      (fn [this]
        (reset-state!))})))

(defn OrgUsers
  []
  (let [org-users (r/cursor state [:org-users])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (get-org-users!)
        [:div
         [InviteMemberModal]
         [ChangeRoleModal]
         [RemoveModal]
         [UsersTable org-users]])})))

