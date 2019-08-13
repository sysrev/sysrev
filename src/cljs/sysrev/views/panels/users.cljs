(ns sysrev.views.panels.users
  (:require [clojure.string :as str]
            [ajax.core :refer [GET]]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$]]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer [Segment Message MessageHeader]]
            [sysrev.views.panels.user.profile :refer [User]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

(setup-panel-state panel [:users] {:state-var state})

(defn AllUsers []
  (let [users (r/cursor state [:users])
        error-message (r/atom "")
        get-users! (fn []
                     (GET "/api/users/group/public-reviewer"
                          {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                           :handler (fn [response]
                                      (reset! users (->> (-> response :result :users)
                                                         (filter :primary-email-verified))))
                           :error-handler (fn [error-response]
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
      :get-initial-state (fn [this] (get-users!))})))

(defmethod logged-out-content [:users] []
  (fn [child]
    [AllUsers]))

(defmethod panel-content [:users] []
  (fn [child]
    [:div#users-content [AllUsers]]))
