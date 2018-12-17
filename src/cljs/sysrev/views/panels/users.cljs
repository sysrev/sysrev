(ns sysrev.views.panels.users
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.views.semantic :refer [Segment Grid Row Column Icon Message MessageHeader]]
            [sysrev.views.base :refer [panel-content logged-out-content]]))

(def ^:private panel [:users])

(defn User
  [{:keys [email user-id]}]
  [Grid
   [Row
    [Column {:width 2}
     [Icon {:name "user icon"
            :size "huge"}]]
    [Column {:width 5}
     [:a {:href (str "/users/" user-id)} email]]]])

(defn UserDetail
  [user-id]
  [:div
   [:a {:href "/users"} "<< Back to Users"]
   [Segment [User {:email "qux"
                   :user-id user-id}]]])

(defn AllUsers
  []
  (let [users (r/atom {})
        error-message (r/atom "")
        retrieving-users? (r/atom false)
        current-path sysrev.base/active-route
        get-users! (fn []
                     (reset! retrieving-users? true)
                     (GET "/api/users"
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
         (when-not (empty? @users)
           (doall (map
                   (fn [user]
                     ^{:key (:user-id user)}
                     [Segment [User user]]) @users)))])
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
