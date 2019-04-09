(ns sysrev.views.panels.org.users
  (:require [ajax.core :refer [GET POST]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.semantic :refer [Segment Table TableHeader TableBody TableRow TableCell Search SearchResults Button
                                           Modal ModalHeader ModalContent ModalDescription Form Input Message MessageHeader]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:org :users])

(def state (r/cursor app-db [:state :panel panel]))

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

(defn UserRow
  [{:keys [user-id username]}]
  [TableRow
   [TableCell
    [Avatar {:user-id user-id}]
    [UserPublicProfileLink {:user-id user-id :display-name username}]]
   [TableCell ;; permissions and setting them go here
    ]])

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
        org-users-set (->> @(r/cursor state [:org-users])
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
                :on-close #(reset! modal-open false)}
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
         [UsersTable org-users]])
      })))

