(ns sysrev.views.panels.user.profile
  (:require [clojure.string :as str]
            [ajax.core :refer [GET POST PUT]]
            [cljsjs.moment]
            [clojure.spec.alpha :as s]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.base :refer [active-route]]
            [sysrev.croppie :refer [CroppieComponent]]
            [sysrev.markdown :refer [MarkdownComponent]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.util :as util :refer [wrap-prevent-default]]
            [sysrev.views.semantic :refer
             [Segment Header Grid Row Column Icon Image Message MessageHeader Button Select Popup
              Modal ModalContent ModalHeader ModalDescription]])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [setup-panel-state]]))

(setup-panel-state panel [:user :profile] {:state-var state})

(s/def ::ratom #(or (instance? reagent.ratom/RAtom %)
                    (instance? reagent.ratom/RCursor %)))

(defn get-project-invitations!
  "Get all of the invitations that have been sent by the project for which user-id is the admin of"
  [user-id]
  (let [retrieving-invitations? (r/cursor state [:retrieving-invitations?])]
    (reset! retrieving-invitations? true)
    (GET (str "/api/user/" user-id "/invitations/projects")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-invitations? false)
                     (reset! (r/cursor state [:invitations]) (-> response :result :invitations)))})))

(defn get-user!
  [user-id]
  (let [retrieving-users? (r/cursor state [:retrieving-users?])
        user-error-message (r/cursor state [:user :error-message])
        user-atom (r/cursor state [:user])]
    (reset! retrieving-users? true)
    (GET (str "/api/user/" user-id)
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-users? false)
                     (reset! user-atom (-> response :result :user)))
          :error-handler (fn [error-response]
                           (.log js/console (clj->js error-response))
                           (reset! retrieving-users? false)
                           (reset! user-error-message (get-in error-response [:response :error :message])))})))

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
     [:div (-> created js/moment ($ format "YYYY-MM-DD h:mm A"))]
     [:div (str "This user was invited as a " description " to " project-name ".")]
     (when-not (nil? accepted)
       [:div (str "Invitation " (if accepted "accepted " "declined "))])]))

(defn- UserInvitations [user-id]
  (let [invitations (r/cursor state [:invitations])
        retrieving-invitations? (r/cursor state [:retrieving-invitations?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div (doall (for [invitation (filter #(= user-id (:user-id %)) @invitations)]
                       ^{:key (:id invitation)}
                       [InvitationMessage invitation]))])
      :component-did-mount
      (fn [this]
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
                   :handler (fn [response]
                              (reset! confirm-message
                                      (str "You've invited this user to " project-name))
                              (reset! loading? false)
                              (get-project-invitations! @(subscribe [:self/user-id])))
                   :error-handler (fn [error-response]
                                    (reset! loading? false)
                                    (reset! error-message
                                            (str "There was an error inviting this user to "
                                                 project-name)))})))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [options (options-fn @(subscribe [:self/projects]))]
          (when-not (empty? options)
            [:div
             [:div {:style {:display "inline-block"}}
              "Invite this user to "
              [:div {:style {:display "inline-block"
                             :padding-left "0.5em"}}
               [Select {:options options
                        :on-change (fn [e f]
                                     (reset! project-id ($ f :value)))
                        :size "tiny"
                        :disabled (or @loading? @retrieving-invitations?)
                        :value @project-id
                        :placeholder "Select Project"}]]]
             (when-not (nil? @project-id)
               [:div {:style {:padding-top "1em"}}
                [Button {:on-click #(do
                                      (create-invitation! user-id @project-id)
                                      (reset! project-id nil))
                         :basic true
                         :color "green"
                         :disabled (or @loading? @retrieving-invitations?)
                         :size "tiny"} "Invite"]
                [Button {:on-click #(do (reset! project-id nil))
                         :basic true
                         :color "red"
                         :disabled (or @loading? @retrieving-invitations?)
                         :size "tiny"} "Cancel"]])
             (when-not (str/blank? @error-message)
               [Message {:onDismiss #(reset! error-message nil)
                         :negative true}
                [MessageHeader "Invitation Error"]
                @error-message])])))
      :get-initial-state
      (fn [this]
        (reset! loading? false)
        nil)})))

(defn UserPublicProfileLink
  [{:keys [user-id display-name]}]
  [:a.user-public-profile {:href (str "/user/" user-id "/profile")} display-name])

(defn Avatar
  [{:keys [user-id]}]
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
          (r/as-component
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
  (let [editing? (r/cursor state [:editing-profile?])
        mutable? (= user-id @(subscribe [:self/user-id]))
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

(defn- EditIntroduction
  [{:keys [editing? mutable? blank?]}]
  (when (and mutable? (not @editing?))
    [:a {:id "edit-introduction"
         :href "#" :on-click (wrap-prevent-default #(swap! editing? not))}
     "Edit"]))

(s/def ::introduction ::ratom)
(s/def ::mutable? boolean?)
(s/def ::user-id integer?)

#_(s/fdef Introduction
    :args (s/keys :req-un [::mutable? ::introduction ::user-id]))

(defn- Introduction
  "Display introduction and edit if mutable? is true"
  [{:keys [mutable? introduction user-id]}]
  (let [editing? (r/cursor state [:user :editing?])
        loading? (r/cursor state [:user :loading])
        retrieving-users? (r/cursor state [:retrieving-users?])
        set-markdown! (fn [user-id]
                        (fn [draft-introduction]
                          (reset! loading? true)
                          (PUT (str "/api/user/" user-id "/introduction")
                               {:params {:introduction draft-introduction}
                                :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                :handler (fn [response]
                                           (get-user! user-id))
                                :error-handler (fn [error-response]
                                                 (reset! loading? false)
                                                 (reset! editing? false))})))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when (or (not (str/blank? @introduction))
                  mutable?)
          [Segment {:class "introduction"}
           [Header {:as "h4"
                    :dividing true}
            "Introduction"]
           [MarkdownComponent {:content @introduction
                               :set-content! (set-markdown! user-id)
                               :loading? (boolean (or @loading? @retrieving-users?))
                               :mutable? mutable?
                               :editing? editing?}]
           [EditIntroduction {:editing? editing?
                              :mutable? mutable?
                              :blank? (r/track #(str/blank? @%) introduction)}]]))
      :get-initial-state
      (fn [this]
        (reset! editing? false)
        (reset! loading? false)
        {})})))

(defn- ProfileSettings
  [{:keys [user-id username]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    (if @editing?
      [EditingUser {:user-id user-id :username username }]
      [User {:user-id user-id :username username}])))

(defn Profile
  [{:keys [user-id]}]
  (let [user (r/cursor state [:user])
        introduction (r/cursor state [:user :introduction])
        error-message (r/cursor state [:user :error-message])
        mutable? (= user-id @(subscribe [:self/user-id]))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (if (str/blank? @error-message)
          ;; display user
          (when-not (nil? @user)
            [:div
             [ProfileSettings @user]
             [Introduction {:mutable? mutable?
                            :introduction introduction
                            :user-id user-id}]])
          ;; error message
          [Message {:negative true}
           [MessageHeader "Error Retrieving User"]
           @error-message]))
      :component-will-receive-props
      (fn [this new-argv]
        (get-user! (-> new-argv second :user-id)))
      :component-did-mount
      (fn [this]
        (reset! user nil)
        (get-user! user-id))
      :component-did-umount
      (fn [this]
        (reset! user nil))
      :get-initial-state
      (fn [this]
        (reset! user nil)
        {})})))
