(ns sysrev.views.panels.users
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.base]
            [sysrev.views.semantic :refer [Segment Grid Row Column Icon Message MessageHeader Button Select]]
            [sysrev.views.base :refer [panel-content logged-out-content]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:users])

(defn InviteUser
  [user-id]
  (let [project-id (r/atom nil)
        options-fn (fn [projects]
                     (->> projects
                          (filter (fn [project]
                                    (some #(= "admin" %) (:permissions project))))
                          (map #(hash-map :key (:project-id %) :text (:name %) :value (:project-id %)))
                          (sort-by :key)
                          (into [])))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div {:style {:display "inline-block"}}
         "Invite this user to " [Select {:options (options-fn @(subscribe [:self/projects]))
                                         :on-change (fn [e f]
                                                      (reset! project-id ($ f :value)))
                                         :size "small"
                                         :placeholder "Select Project"}]
         [Button {:on-click #(.log js/console "I would have invited this user to " @project-id)
                  :basic true
                  :color "green"
                  :disabled (nil? @project-id)
                  :size "mini"} "Invite"]])})))

(defn User
  [{:keys [email user-id]}]
  [Grid
   [Row
    [Column {:width 2}
     [Icon {:name "user icon"
            :size "huge"}]]
    [Column {:width 5}
     [:a {:href (str "/users/" user-id)} (first (clojure.string/split email #"@"))]
     [InviteUser]
     ]]])

(defn UserDetail
  [user-id]
  (let [user (r/atom {})
        error-message (r/atom "")
        retrieving-users? (r/atom false)
        get-user! (fn []
                    (reset! retrieving-users? true)
                    (GET (str "/api/users/public-reviewer/" user-id)
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
            [User @user]
            [:a {:on-click (fn [e]
                             (.preventDefault e))} "invite this user to a project"]]
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
                     (GET "/api/users/public-reviewer"
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
