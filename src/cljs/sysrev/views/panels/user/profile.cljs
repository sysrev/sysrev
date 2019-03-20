(ns sysrev.views.panels.user.profile
  (:require [ajax.core :refer [GET POST PUT]]
            [cljsjs.moment]
            [clojure.spec.alpha :as s]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [re-frame.core :refer [subscribe]]
            [sysrev.base :refer [active-route]]
            [sysrev.croppie :refer [CroppieComponent]]
            [sysrev.markdown :refer [MarkdownComponent]]
            [sysrev.util :as util]
            [sysrev.views.semantic :refer [Segment Header Grid Row Column Icon Image Message MessageHeader
                                           Button Select Divider Popup
                                           Modal ModalContent ModalHeader ModalDescription]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:prviate panel [:state :panels :user :profile])

(def state (r/cursor app-db [:state :panels panel]))

(s/def ::ratom #(instance? reagent.ratom/RAtom %))

(defn condensed-number
  "Condense numbers over 1000 to be factors of k"
  [i]
  (when (= i 0)
    (str 0))
  (if (> i 999)
    (-> (/ i 1000)  ($ toFixed 1) (str "K"))
    (str i)))

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

(defn Invitation
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

(defn Invitations
  [user-id]
  (let [invitations (r/cursor state [:invitations])
        retrieving-invitations? (r/cursor state [:retrieving-invitations?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         (map (fn [invitation]
                ^{:key (:id invitation)}
                [Invitation invitation])
              (filter #(= user-id (:user-id %)) @invitations))])
      :get-initial-state
      (fn [this]
        (when (and (nil? @invitations)
                   (not @retrieving-invitations?))
          (get-project-invitations! @(subscribe [:self/user-id]))))})))

(defn InviteUser
  [user-id]
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
             (when-not (clojure.string/blank? @error-message)
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
  [:a {:href (str "/users/" user-id)
       :style {:margin-left "0.25em"}} display-name])

(defn Avatar
  [{:keys [user-id]}]
  (let [reload-avatar? (r/cursor state [:reload-avatar?])]
    (if @reload-avatar?
      (reset! reload-avatar? false)
      [Image {:src (str "/api/user/" user-id "/avatar")
              :avatar true
              :display (str @reload-avatar?)}])))

(defn ProfileAvatar
  [{:keys [user-id modal-open]}]
  (let [reload-avatar? (r/cursor state [:reload-avatar?])]
    (if @reload-avatar?
      (reset! reload-avatar? false)
      [Image {:src (str "/api/user/" user-id "/avatar")
              :circular true
              :style {:cursor "pointer"}
              :alt "error"}])))

(defn AvatarModal
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

(defn UserAvatar
  [{:keys [mutable? user-id modal-open]}]
  (if mutable?
    [AvatarModal {:user-id user-id
                  :modal-open modal-open}]
    [ProfileAvatar {:user-id user-id
                    :modal-open (constantly false)}]))

(defn UserInteraction
  [{:keys [user-id email]}]
  [:div
   [UserPublicProfileLink {:user-id user-id :display-name (first (clojure.string/split email #"@"))}]
   [:div
    (when-not (= user-id @(subscribe [:self/user-id]))
      [InviteUser user-id])
    [:div {:style {:margin-top "1em"}}
     [Invitations user-id]]]])

(defn User
  [{:keys [email user-id]}]
  (let [editing? (r/cursor state [:editing-profile?])
        mutable? (= user-id @(subscribe [:self/user-id]))
        modal-open (r/cursor state [:avatar-model-open])]
    [Segment {:class "user"}
     [Grid
      ;; computer / tablet
      [Row (cond-> {}
             (util/mobile?) (assoc :columns 3))
       [Column (cond-> {}
                 (not (util/mobile?)) (assoc :width 2))
        [UserAvatar
         {:mutable? mutable? :user-id user-id :modal-open modal-open}]]
       [Column
        [UserInteraction {:user-id user-id :email email}]]]]]))

(defn EditingUser
  [{:keys [user-id email]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    [Segment {:class "editing-user"}
     [Grid
      [Row
       [Column {:width 2}
        [Icon {:name "user icon"
               :size "huge"}]]
       [Column {:width 12}
        [UserPublicProfileLink {:user-id user-id :display-name (first (clojure.string/split email #"@"))}]
        [:div
         [:a {:on-click (fn [e]
                          ($ e :preventDefault)
                          (swap! editing? not))
              :href "#"}
          "Save Profile"]]
        [:div
         (when-not (= user-id @(subscribe [:self/user-id]))
           [InviteUser user-id])
         [:div {:style {:margin-top "1em"}}
          [Invitations user-id]]]]]]]))

(defn EditIntroduction
  [{:keys [editing? mutable? blank?]}]
  (when mutable?
    (when (not @editing?)
      [:a {:href "#"
           :id "edit-introduction"
           :on-click (fn [event]
                       ($ event preventDefault)
                       (swap! editing? not))}
       "Edit"])))

(s/def ::introduction ::ratom)
(s/def ::mutable? boolean?)
(s/def ::user-id integer?)

(s/fdef Introduction
  :args (s/keys :req-un [::mutable? ::introduction ::user-id]))

(defn Introduction
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
        (when (or (not (clojure.string/blank? @introduction))
                  mutable?)
          [Segment {:class "introduction"}
           [Header {:as "h4"
                    :dividing true}
            "Introduction"]
           [MarkdownComponent {:markdown introduction
                               :set-markdown! (set-markdown! user-id)
                               :loading? (fn []
                                           (or @loading?
                                               @retrieving-users?))
                               :mutable? mutable?
                               :editing? editing?}]
           [EditIntroduction {:editing? editing?
                              :mutable? mutable?
                              :blank? (r/track #(clojure.string/blank? @%) introduction)}]]))
      :get-initial-state
      (fn [this]
        (reset! editing? false)
        (reset! loading? false)
        {})})))

(defn ActivitySummary
  [{:keys [articles labels annotations count-font-size]}]
  (let [header-margin-bottom "0.10em"]
    (when (or (> articles 0)
              (> labels 0)
              (> annotations 0))
      [Grid {:columns 3
             :style {:display "block"}}
       [Row
        (when (> articles 0)
          [Column
           [:h2 {:style (cond-> {:margin-bottom header-margin-bottom}
                          count-font-size (assoc :font-size count-font-size))
                 :class "articles-reviewed"} (condensed-number articles)]
           [:p "Articles Reviewed"]])
        (when (> labels 0)
          [Column
           [:h2 {:style (cond-> {:margin-bottom header-margin-bottom}
                          count-font-size (assoc :font-size count-font-size))
                 :class "labels-contributed"} (condensed-number labels)]
           [:p "Labels Contributed"]])
        (when (> annotations 0)
          [Column
           [:h2 {:style (cond-> {:margin-bottom header-margin-bottom}
                          count-font-size (assoc :font-size count-font-size))
                 :class "annotations-contributed"} (condensed-number annotations)]
           [:p "Annotations Contributed"]])]])))

(defn Project
  [{:keys [name project-id articles labels annotations]
    :or {articles 0
         labels 0
         annotations 0}}]
  [:div {:style {:margin-bottom "1em"}
         :id (str "project-" project-id)}
   [:a {:href (str "/p/" project-id)
        :style {:margin-bottom "0.5em"
                :display "block"}} name]
   [ActivitySummary {:articles articles
                     :labels labels
                     :annotations annotations
                     :count-font-size "1em"}]
   [Divider]])

(defn UserProjects
  [{:keys [user-id]}]
  (let [projects (r/cursor state [:projects])
        error-message (r/atom "")
        retrieving-projects? (r/atom false)
        get-user-projects! (fn [user-id projects-atom]
                             (reset! retrieving-projects? true)
                             (GET (str "/api/user/" user-id "/projects")
                                  {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                   :handler (fn [response]
                                              (reset! retrieving-projects? false)
                                              (reset! projects-atom (-> response :result :projects)))
                                   :error-handler (fn [error-response]
                                                    (.log js/console (clj->js error-response))
                                                    (reset! retrieving-projects? false)
                                                    (reset! error-message (get-in error-response [:response :error :message])))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [public private]} (group-by #(if (get-in % [:settings :public-access]) :public :private) @projects)
              activity-summary (fn [{:keys [articles labels annotations]}]
                                 (+ articles labels annotations))
              sort-fn #(> (activity-summary %1)
                          (activity-summary %2))
              ;; because we need to exclude anything that doesn't explicitly have a settings keyword
              ;; non-public project summaries are given, but without identifying profile information
              private (filter #(contains? % :settings) private)]
          (when-not (empty? @projects)
            [:div.projects
             (when-not (empty? public)
               [Segment
                [Header {:as "h4"
                         :dividing true}
                 "Projects"]
                [:div {:id "public-projects"}
                 (->> public
                      (sort sort-fn)
                      (map (fn [project]
                             ^{:key (:project-id project)}
                             [Project project])))]])
             (when-not (empty? private)
               [Segment
                [Header {:as "h4"
                         :dividing true}
                 "Private Projects"]
                [:div {:id "private-projects"}
                 (->> private
                      (sort sort-fn)
                      (map (fn [project]
                             ^{:key (:project-id project)}
                             [Project project])))]])])))
      :component-will-receive-props
      (fn [this new-argv]
        (get-user-projects! (-> new-argv second :user-id) projects))
      :component-did-mount (fn [this]
                             (get-user-projects! user-id projects))})))

(defn UserActivitySummary
  [projects]
  (let [count-items (fn [projects kw]
                      (->> projects (map kw) (apply +)))
        articles (count-items projects :articles)
        labels (count-items projects :labels)
        annotations (count-items projects :annotations)]
    (when (> (+ articles labels annotations) 0)
      [Segment {:id "user-activity-summary"}
       [ActivitySummary {:articles articles
                         :labels labels
                         :annotations annotations}]])))

(defn ProfileSettings
  [{:keys [user-id email]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    (if @editing?
      [EditingUser {:user-id user-id :email email }]
      [User {:user-id user-id :email email}])))

(defn Profile
  [{:keys [user-id email]}]
  (let [user (r/cursor state [:user])
        introduction (r/cursor state [:user :introduction])
        error-message (r/cursor state [:user :error-message])
        projects (r/cursor state [:projects])
        mutable? (= user-id @(subscribe [:self/user-id]))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (if (clojure.string/blank? @error-message)
          ;; display user
          [:div
           [ProfileSettings @user]
           [UserActivitySummary @projects]
           [Introduction {:mutable? mutable?
                          :introduction introduction
                          :user-id user-id}]
           [UserProjects @user]]
          ;; error message
          [Message {:negative true}
           [MessageHeader "Error Retrieving User"]
           @error-message]))
      :get-initial-state
      (fn [this]
        (get-user! user-id))})))
