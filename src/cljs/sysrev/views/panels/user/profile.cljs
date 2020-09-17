(ns sysrev.views.panels.user.profile
  (:require ["moment" :as moment]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [ajax.core :refer [GET POST PUT]]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.loading :as loading]
            [sysrev.croppie :refer [CroppieComponent]]
            [sysrev.markdown :refer [MarkdownComponent]]
            [sysrev.state.ui]
            [sysrev.state.nav :refer [user-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer
             [Segment Header Grid Row Column Icon Image Message MessageHeader Button Select
              Modal ModalContent ModalHeader ModalDescription]]
            [sysrev.util :as util :refer [parse-integer wrap-prevent-default]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:user :profile] {:state-var state
                                           :get-fn panel-get :set-fn panel-set
                                           :get-sub ::get :set-event ::set})

(s/def ::ratom #(or (instance? ratom/RAtom %)
                    (instance? ratom/RCursor %)))

(defn get-project-invitations!
  "Get all of the invitations that have been sent by the project for which user-id is the admin of"
  [user-id]
  (let [retrieving-invitations? (r/cursor state [:retrieving-invitations?])]
    (reset! retrieving-invitations? true)
    (GET (str "/api/user/" user-id "/invitations/projects")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-invitations? false)
                     (reset! (r/cursor state [:invitations])
                             (-> response :result :invitations)))})))

(def-data :user/info
  :uri (fn [user-id] (str "/api/user/" user-id))
  :loaded? (fn [db user-id]
             (-> (get-in db [:data :users-public user-id])
                 (contains? :info)))
  :process (fn [{:keys [db]} [user-id] {:keys [user]}]
             {:db (assoc-in db [:data :users-public user-id :info] user)})
  :on-error (fn [{:keys [db error]} [user-id] _]
              (let [{:keys [message]} error]
                {:db (panel-set db [:user user-id :info-error] message)})))

(reg-sub :user/info
         (fn [[_ user-id]] (subscribe [:user/get user-id]))
         (fn [user] (:info user)))

(defn- InvitationMessage
  [{:keys [project-id description accepted active created]}]
  (let [projects @(subscribe [:self/projects])
        project-name (->> projects
                          (filter #(= project-id (:project-id %)))
                          first
                          :name)]
    [Message (cond-> {}
               accepted (merge {:positive true})
               (false? accepted) (merge {:negative true}))
     [:div (-> created (moment.) (.format "YYYY-MM-DD h:mm A"))]
     [:div (str "This user was invited as a " description " to " project-name ".")]
     (when-not (nil? accepted)
       [:div (str "Invitation " (if accepted "accepted " "declined "))])]))

(defn- UserInvitations [user-id]
  (let [invitations (r/cursor state [:invitations])
        retrieving-invitations? (r/cursor state [:retrieving-invitations?])]
    (r/create-class
     {:reagent-render
      (fn [_]
        [:div (doall (for [invitation (filter #(= user-id (:user-id %)) @invitations)]
                       ^{:key (:id invitation)}
                       [InvitationMessage invitation]))])
      :component-did-mount
      (fn [_this]
        (when (and (nil? @invitations)
                   (not @retrieving-invitations?))
          (get-project-invitations! @(subscribe [:self/user-id]))))})))

(defn- InviteUser [user-id]
  (let [project-id (r/atom nil)
        loading? (r/atom true)
        retrieving-invitations? (r/cursor state [:retrieving-invitations?])
        error-message (r/atom "")
        confirm-message (r/atom "")
        invitations (r/cursor state [:invitations])
        options-fn
        (fn [projects]
          (let [project-invitations (->> @invitations
                                         (filter #(= (:user-id %) user-id))
                                         (map :project-id))]
            (->> projects
                 (filter #(some (partial = "admin") (:permissions %)))
                 (filter #(not (some (partial = (:project-id %)) project-invitations)))
                 (map #(hash-map :key (:project-id %)
                                 :text (:name %)
                                 :value (:project-id %)))
                 (sort-by :key)
                 (into []))))
        create-invitation!
        (fn [invitee project-id]
          (let [project-name (->> @(subscribe [:self/projects])
                                  options-fn
                                  (filter #(= (:value %) project-id))
                                  first
                                  :text)]
            (reset! loading? true)
            (POST (str "/api/user/" @(subscribe [:self/user-id])
                       "/invitation/" invitee "/" project-id)
                  {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                   :params {:description "paid-reviewer"}
                   :handler (fn [_response]
                              (reset! confirm-message
                                      (str "You've invited this user to " project-name))
                              (reset! loading? false)
                              (get-project-invitations! @(subscribe [:self/user-id])))
                   :error-handler (fn [_response]
                                    (reset! loading? false)
                                    (reset! error-message
                                            (str "There was an error inviting this user to "
                                                 project-name)))})))]
    (r/create-class
     {:reagent-render
      (fn [_]
        (let [options (options-fn @(subscribe [:self/projects]))]
          (when-not (empty? options)
            [:div
             [:div {:style {:display "inline-block"}}
              "Invite this user to "
              [:div {:style {:display "inline-block"
                             :padding-left "0.5em"}}
               [Select {:options options
                        :on-change (fn [_e ^js f] (reset! project-id (.-value f)))
                        :size "tiny"
                        :disabled (or @loading? @retrieving-invitations?)
                        :value @project-id
                        :placeholder "Select Project"}]]]
             (when-not (nil? @project-id)
               [:div {:style {:padding-top "1em"}}
                [Button {:on-click #(do (create-invitation! user-id @project-id)
                                        (reset! project-id nil))
                         :color "green"
                         :disabled (or @loading? @retrieving-invitations?)
                         :size "tiny"} "Invite"]
                [Button {:on-click #(reset! project-id nil)
                         :disabled (or @loading? @retrieving-invitations?)
                         :size "tiny"} "Cancel"]])
             (when-not (str/blank? @error-message)
               [Message {:onDismiss #(reset! error-message nil)
                         :negative true}
                [MessageHeader "Invitation Error"]
                @error-message])])))
      :get-initial-state
      (fn [_this]
        (reset! loading? false)
        nil)})))

(defn UserPublicProfileLink [{:keys [user-id display-name]}]
  [:a.user-public-profile {:href (user-uri user-id)
                           :data-username display-name}
   display-name])

(defn Avatar [{:keys [user-id]}]
  (let [reload-avatar? (r/cursor state [:reload-avatar?])]
    (if @reload-avatar?
      (reset! reload-avatar? false)
      [Image {:src (str "/api/user/" user-id "/avatar")
              :avatar true
              :display (str @reload-avatar?)
              :class "sysrev-avatar"
              :alt ""}])))

(defn ProfileAvatar
  [{:keys [user-id]}]
  (let [reload-avatar? (r/cursor state [:reload-avatar?])]
    (if @reload-avatar?
      (reset! reload-avatar? false)
      [Image {:src (str "/api/user/" user-id "/avatar")
              :circular true
              :style {:cursor "pointer"}
              :alt ""}])))

(defn- AvatarModal
  [{:keys [user-id modal-open]}]
  [Modal {:trigger
          (r/as-element
           [:div.ui {:data-tooltip "Change Your Avatar"
                     :data-position "bottom center"}
            [ProfileAvatar {:user-id user-id
                            :modal-open #(reset! modal-open true)}]])
          :open @modal-open
          :on-open #(reset! modal-open true)
          :on-close #(reset! modal-open false)}
   [ModalHeader "Edit Your Avatar"]
   [ModalContent
    [ModalDescription
     [CroppieComponent {:user-id user-id
                        :modal-open modal-open
                        :reload-avatar? (r/cursor state [:reload-avatar?])}]]]])

(defn- UserAvatar
  [{:keys [mutable? user-id modal-open]}]
  (if mutable?
    [AvatarModal {:user-id user-id
                  :modal-open modal-open}]
    [ProfileAvatar {:user-id user-id
                    :modal-open (constantly false)}]))

(defn- UserInteraction
  [{:keys [user-id username]}]
  [:div
   [UserPublicProfileLink {:user-id user-id :display-name username}]
   [:div
    (when-not (= user-id @(subscribe [:self/user-id]))
      [InviteUser user-id])
    [:div {:style {:margin-top "1em"}}
     [UserInvitations user-id]]]])

(defn User
  [{:keys [username user-id]}]
  (let [mutable? (= user-id @(subscribe [:self/user-id]))
        modal-open (r/cursor state [:avatar-model-open])]
    [Segment {:class "user"}
     [Grid {:columns "equal"}
      ;; computer / tablet
      [Row (cond-> {}
             (util/mobile?) (assoc :columns 3))
       [Column (cond-> {}
                 (not (util/mobile?)) (assoc :width 2))
        [UserAvatar
         {:mutable? mutable? :user-id user-id :modal-open modal-open}]]
       [Column
        [UserInteraction {:user-id user-id :username username}]]]]]))

(defn- EditingUser
  [{:keys [user-id username]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    [Segment {:class "editing-user"}
     [Grid
      [Row
       [Column {:width 2}
        [Icon {:name "user icon" :size "huge"}]]
       [Column {:width 12}
        [UserPublicProfileLink {:user-id user-id :display-name username}]
        [:div>a {:href "#" :on-click (wrap-prevent-default #(swap! editing? not))}
         "Save Profile"]
        [:div
         (when-not (= user-id @(subscribe [:self/user-id]))
           [InviteUser user-id])
         [:div {:style {:margin-top "1em"}}
          [UserInvitations user-id]]]]]]]))

(defn- EditIntroductionLink
  [{:keys [editing? mutable? blank?]}]
  (when (and mutable? (not @editing?))
    [:a {:id "edit-introduction"
         :href "#" :on-click (wrap-prevent-default #(swap! editing? not))}
     "Edit"]))

(s/def ::introduction (s/nilable string?))
(s/def ::mutable? boolean?)
(s/def ::user-id integer?)

#_(s/fdef Introduction
    :args (s/keys :req-un [::mutable? ::introduction ::user-id]))

(defn- Introduction
  "Display introduction and edit if mutable? is true"
  [{:keys [mutable? introduction user-id]}]
  (let [editing? (r/cursor state [:introduction :editing?])
        loading? (r/cursor state [:introduction :loading])
        set-markdown! (fn [user-id]
                        (fn [draft-introduction]
                          (reset! loading? true)
                          (PUT (str "/api/user/" user-id "/introduction")
                               {:params {:introduction draft-introduction}
                                :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                :handler (fn [_response]
                                           (dispatch [:reload [:user/info user-id]])
                                           (reset! editing? false)
                                           (reset! loading? false))
                                :error-handler (fn [_response]
                                                 (reset! loading? false)
                                                 (reset! editing? false))})))
        user-loading? (loading/item-loading? [:user/info user-id])]
    (when (or mutable? (not (str/blank? introduction)))
      [Segment {:class "introduction"}
       [Header {:as "h4" :dividing true} "Introduction"]
       [MarkdownComponent {:content introduction
                           :set-content! (set-markdown! user-id)
                           :loading? (boolean (or @loading? user-loading?))
                           :mutable? mutable?
                           :editing? editing?}]
       [EditIntroductionLink {:editing? editing?
                              :mutable? mutable?
                              :blank? (str/blank? introduction)}]])))

(defn- ProfileSettings [{:keys [user-id username]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    (if @editing?
      [EditingUser {:user-id user-id :username username }]
      [User {:user-id user-id :username username}])))

(defn UserProfile [user-id]
  (let [{:keys [introduction] :as user} @(subscribe [:user/info user-id])
        error-message @(subscribe [::get [:user user-id :info-error]])
        self? @(subscribe [:user-panel/self?])]
    (dispatch [:require [:user/info user-id]])
    (if (str/blank? error-message)
      (when user
        [:div
         [ProfileSettings user]
         [Introduction {:mutable? self?
                        :introduction introduction
                        :user-id user-id}]])
      [Message {:negative true}
       [MessageHeader "Error Retrieving User"]
       error-message])))

(defn UserProfilePanel []
  (when-let [user-id @(subscribe [:user-panel/user-id])]
    [UserProfile user-id]))

(defmethod panel-content panel []
  (fn [_child] [UserProfilePanel]))

(defn- go-user-profile-route [user-id]
  (let [user-id (parse-integer user-id)]
    (dispatch [:user-panel/set-user-id user-id])
    (dispatch [::set [] {}])
    (dispatch [:data/load [:user/info user-id]])
    (when @(subscribe [:user-panel/self?])
      (dispatch [:reload [:user/payments-owed user-id]])
      (dispatch [:reload [:user/payments-paid user-id]])
      (dispatch [:user/get-invitations!]))
    (dispatch [:set-active-panel panel])))

(sr-defroute user-root #"/user/(\d+)$" [user-id]
             (go-user-profile-route user-id))

(sr-defroute user-profile "/user/:user-id/profile" [user-id]
             (go-user-profile-route user-id))
