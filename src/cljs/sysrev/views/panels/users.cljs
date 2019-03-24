(ns sysrev.views.panels.users
  (:require [clojure.string :as str]
            [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.base]
            [sysrev.views.panels.user.profile :refer [User Profile]]
            [sysrev.views.semantic :refer [Segment Message MessageHeader]]
            [sysrev.views.base :refer [panel-content logged-out-content]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:users])

(def state (r/cursor app-db [:state :panels panel]))

(defn AllUsers
  []
  (let [users (r/cursor state [:users])
        error-message (r/atom "")
        retrieving-users? (r/atom false)
        current-path sysrev.base/active-route
        get-users! (fn []
                     (reset! retrieving-users? true)
                     (GET "/api/users/group/public-reviewer"
                          {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                           :handler (fn [response]
                                      (reset! retrieving-users? false)
                                      (reset! users (->> (-> response :result :users)
                                                         (filter :primary-email-verified))))
                           :error-handler (fn [error-response]
                                            (reset! retrieving-users? false)
                                            (reset! error-message "There was a problem retrieving users"))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [Segment
          [:h4 "Users"]]
         (when-not (str/blank? @error-message)
           [Message {:onDismiss #(reset! error-message nil)
                     :negative true}
            [MessageHeader "Retrieving Users Error"]
            @error-message])
         (if-not (empty? @users)
           (doall (map
                   (fn [user]
                     ^{:key (:user-id user)}
                     [User user])
                   @users))
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
         (re-matches #"/users/{0,1}$" @current-path)
         [AllUsers]
         (re-matches #"/users/\d+" @current-path)
         [Profile {:user-id (js/parseInt (second (re-matches #"/users/(\d+)" @current-path)))}])])))

(defmethod logged-out-content [:users] []
  (logged-out-content :logged-out))

(defmethod panel-content [:users] []
  (fn [child]
    [Users]))
