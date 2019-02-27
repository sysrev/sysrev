(ns sysrev.views.panels.user.profile
  (:require [ajax.core :refer [GET POST]]
            [cljsjs.moment]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [re-frame.core :refer [subscribe]]
            [sysrev.base :refer [active-route]]
            [sysrev.markdown :refer [MarkdownComponent]]
            [sysrev.views.semantic :refer [Segment Header Grid Row Column Icon Message MessageHeader Button Select]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:prviate panel [:state :panels :user :profile])

(def state (r/cursor app-db [:state :panels panel]))

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
                  {:params {:description "paid-reviewer"}
                   :headers {"x-csrf-token" @(subscribe [:csrf-token])}
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
  "Should also handle permissions and determine whether or not to display a link"
  [{:keys [user-id display-name]}]
  [:a {:href (str "/users/" user-id)} display-name])

(defn User
  [{:keys [email user-id]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    [Segment
     [Grid
      [Row
       [Column {:width 2}
        [Icon {:name "user icon"
               :size "huge"}]]
       [Column {:width 12}
        [UserPublicProfileLink {:user-id user-id :display-name (first (clojure.string/split email #"@"))}]
        [:div
         #_[:a {:on-click (fn [e]
                            ($ e :preventDefault)
                            (swap! editing? not))
                :href "#"}
            "Edit Profile"]]
        [:div
         (when-not (= user-id @(subscribe [:self/user-id]))
           [InviteUser user-id])
         [:div {:style {:margin-top "1em"}}
          [Invitations user-id]]]]]]]))

(defn EditingUser
  [{:keys [user-id email]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    [Segment [Grid
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
           :on-click (fn [event]
                       ($ event preventDefault)
                       (swap! editing? not))}
       "Edit"])))

(defn Introduction
  [{:keys [mutable?]}]
  (let [markdown (r/atom "")
        editing? (r/atom false)
        set-markdown! (fn [draft-markdown]
                        (reset! markdown draft-markdown)
                        (reset! editing? false))
        loading? (constantly false)]
    (when (or (not (clojure.string/blank? @markdown))
              mutable?)
      [Segment
       [Header {:as "h4"
                :dividing true}
        "Introduction"]
       [MarkdownComponent {:markdown markdown
                           :set-markdown! set-markdown!
                           :loading? loading?
                           :mutable? true
                           :editing? editing?}]
       [EditIntroduction {:editing? editing?
                          :mutable? mutable?
                          :blank? (r/track #(clojure.string/blank? @%) markdown)}]])))

(defn Project
  [{:keys [name project-id]}]
  [Row [Column {:width 16} [:a {:href (str "/p/" project-id)} name]]])

(defn UserProjects
  [{:keys [user-id]}]
  (let [projects (r/atom [])
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
        (let [{:keys [public private]} (group-by #(if (get-in % [:settings :public-access]) :public :private) @projects)]
          (when-not (empty? @projects)
            [:div
             (when-not (empty? public)
               [Segment
                [Header {:as "h4"
                         :dividing true}
                 "Projects"]
                [Grid
                 (map (fn [project]
                        ^{:key (:project-id project)}
                        [Project project]) public)]])
             (when-not (empty? private)
               [Segment
                [Header {:as "h4"
                         :dividing true}
                 "Private Projects"]
                [Grid
                 (map (fn [project]
                        ^{:key (:project-id project)}
                        [Project project]) private)]])])))
      :component-will-receive-props
      (fn [this new-argv]
        (get-user-projects! (-> new-argv second :user-id) projects))})))

(defn ProfileSettings
  [{:keys [user-id email]}]
  (let [editing? (r/cursor state [:editing-profile?])]
    (if @editing?
      [EditingUser {:user-id user-id :email email }]
      [User {:user-id user-id :email email}])))

(defn Profile
  [{:keys [user-id email]}]
  (let [user (r/atom {})
        error-message (r/atom "")
        retrieving-users? (r/atom false)
        projects (r/atom [])
        mutable? (= user-id @(subscribe [:self/user-id]))
        get-user! (fn [user-id user-atom]
                    (reset! retrieving-users? true)
                    (GET (str "/api/user/" user-id)
                         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                          :handler (fn [response]
                                     (reset! retrieving-users? false)
                                     (reset! user-atom (-> response :result :user)))
                          :error-handler (fn [error-response]
                                           (.log js/console (clj->js error-response))
                                           (reset! retrieving-users? false)
                                           (reset! error-message (get-in error-response [:response :error :message])))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (.log js/console "user " (clj->js @user))
        (if (clojure.string/blank? @error-message)
          ;; display user
          [:div
           [ProfileSettings @user]
           [Introduction {:mutable? mutable?}]
           [UserProjects @user]]
          ;; error message
          [Message {:negative true}
           [MessageHeader "Error Retrieving User"]
           @error-message]))
      :get-initial-state
      (fn [this]
        (get-user! user-id user))})))
