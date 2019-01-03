(ns sysrev.views.panels.users
  (:require [ajax.core :refer [GET POST]]
            [cljsjs.moment]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.base]
            [sysrev.views.semantic :refer [Segment Grid Row Column Icon Message MessageHeader Button Select]]
            [sysrev.views.base :refer [panel-content logged-out-content]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:users])

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
               (not accepted) (merge {:negative true})
               (nil? accepted) {})
     [:div (-> created js/moment ($ format "YYYY-MM-DD h:mm A"))]
     [:div (str "This user was invited as a " description " to " project-name ".")]
     (when-not (nil? accepted)
       [:div (str "Invitation "
                  (if accepted
                    "accepted "
                    "declined "))])]))

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
        options-fn (fn [projects]
                     (let [project-invitations (->> @invitations
                                                    (filter #(= (:user-id %) user-id))
                                                    (map :project-id))]
                       (->> projects
                            (filter #(some (partial = "admin") (:permissions %)))
                            (filter #(not (some (partial = (:project-id %)) project-invitations)))
                            (map #(hash-map :key (:project-id %) :text (:name %) :value (:project-id %)))
                            (sort-by :key)
                            (into []))))
        create-invitation! (fn [invitee project-id]
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
                                                 (reset! confirm-message (str "You've invited this user to " project-name))
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
            [:div {:style {:display "inline-block"}}
             "Invite this user to " [Select {:options options
                                             :on-change (fn [e f]
                                                          (reset! project-id ($ f :value)))
                                             :size "small"
                                             :disabled (or @loading? @retrieving-invitations?)
                                             :value @project-id
                                             :placeholder "Select Project"}]
             [Button {:on-click #(do
                                   (create-invitation! user-id @project-id)
                                   (reset! project-id nil))
                      :basic true
                      :color "green"
                      :disabled (or @loading? @retrieving-invitations?)
                      :size "mini"} "Invite"]
             (when-not (clojure.string/blank? @error-message)
               [Message {:onDismiss #(reset! error-message nil)
                         :negative true}
                [MessageHeader "Invitation Error"]
                @error-message])])))
      :get-initial-state
      (fn [this]
        (reset! loading? false)
        nil)})))

(defn User
  [{:keys [email user-id]}]
  [Grid
   [Row
    [Column {:width 2}
     [Icon {:name "user icon"
            :size "huge"}]]
    [Column {:width 12}
     [:a {:href (str "/users/" user-id)} (first (clojure.string/split email #"@"))]
     [:div
      (when-not (= user-id @(subscribe [:self/user-id]))
        [InviteUser user-id])
      [:div {:style {:margin-top "1em"}}
       [Invitations user-id]]]]]])

(defn UserDetail
  [user-id]
  (let [user (r/atom {})
        error-message (r/atom "")
        retrieving-users? (r/atom false)
        get-user! (fn []
                    (reset! retrieving-users? true)
                    (GET (str "/api/users/group/public-reviewer/" user-id)
                         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                          :handler (fn [response]
                                     (reset! retrieving-users? false)
                                     (reset! user (-> response :result :user)))
                          :error-handler (fn [error-response]
                                           (reset! retrieving-users? false)
                                           (reset! error-message "There was a problem retrieving this user"))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:a {:href "/users"} "<< Back to Users"]
         (if (clojure.string/blank? @error-message)
           [Segment
            [User @user]]
           [Message {:onDismiss #(reset! error-message nil)
                     :negative true}
            [MessageHeader "Retrieving User Error"]
            @error-message])])
      :get-initial-state
      (fn [this]
        (get-user!))})))

(defn AllUsers
  []
  (let [users (r/atom {})
        error-message (r/atom "")
        retrieving-users? (r/atom false)
        current-path sysrev.base/active-route
        get-users! (fn []
                     (reset! retrieving-users? true)
                     (GET "/api/users/group/public-reviewer"
                          {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                           :handler (fn [response]
                                      (reset! retrieving-users? false)
                                      (reset! users (-> response :result :users)))
                           :error-handler (fn [error-response]
                                            (reset! retrieving-users? false)
                                            (reset! error-message "There was a problem retrieving users"))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [Segment
          [:h4 "Users"]]
         (when-not (clojure.string/blank? @error-message)
           [Message {:onDismiss #(reset! error-message nil)
                     :negative true}
            [MessageHeader "Retrieving Users Error"]
            @error-message])
         (if-not (empty? @users)
           (doall (map
                   (fn [user]
                     ^{:key (:user-id user)}
                     [Segment [User user]]) @users))
           [Message
            "There currently are no public reviewers"])])
      :get-initial-state
      (fn [this]
        (get-users!))
      })))

(defn Users
  []
  (let [current-path sysrev.base/active-route]
    (fn []
      [:div#users-content
       (cond
         (re-matches #"/users" @current-path)
         [AllUsers]
         (re-matches #"/users/\d*" @current-path)
         [UserDetail (second (re-matches #"/users/(\d*)" @current-path))]
         )])))

(defmethod logged-out-content [:users] []
  (logged-out-content :logged-out))

(defmethod panel-content [:users] []
  (fn [child]
    [Users]))
