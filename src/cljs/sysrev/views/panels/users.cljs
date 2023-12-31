(ns sysrev.views.panels.users
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data load-data]]
            [sysrev.views.semantic :refer [Segment Message]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.views.panels.user.profile :refer [User]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:users]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :public-reviewers
  :loaded?  (fn [db] (-> (get-in db [:data])
                         (contains? :public-reviewers)))
  :uri      "/api/users/group/public-reviewer"
  :process  (fn [{:keys [db]} _ {:keys [users]}]
              {:db (assoc-in db [:data :public-reviewers]
                             (filterv :primary-email-verified users))})
  :on-error (fn [{:keys [db]} _ _]
              (panel-set db :error-msg "There was a problem retrieving users.")))

(reg-sub :public-reviewers #(get-in % [:data :public-reviewers]))

(defn AllUsers []
  (let [error-msg (r/cursor state [:error-msg])]
    [:div
     [Segment [:h4 "Users"]]
     [CursorMessage error-msg {:negative true}]
     (with-loader [[:public-reviewers]] {}
       (let [reviewers @(subscribe [:public-reviewers])]
         (if (seq reviewers)
           (doall (for [user reviewers] ^{:key (:user-id user)}
                    [User user]))
           [Message "There currently are no public reviewers."])))]))

(def-panel :uri "/users" :panel panel
  :on-route (do (dispatch [::set [] {}])
                (load-data :public-reviewers)
                (dispatch [:set-active-panel panel]))
  :content [:div#users-content [AllUsers]]
  :logged-out-content [AllUsers])
