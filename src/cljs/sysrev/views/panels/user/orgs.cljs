(ns sysrev.views.panels.user.orgs
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.util :as util :refer [wrap-prevent-default]]
            [sysrev.views.panels.orgs :refer [CreateOrg]]
            [sysrev.views.semantic :refer [Segment Header Divider]]
            [sysrev.views.panels.user.profile :as user-profile])
  (:require-macros [reagent.interop :refer [$]]))

(def panel user-profile/panel)

(def state (r/cursor app-db [:state :panel panel]))

(reg-sub :users/orgs
         (fn [db [event user-id]]
           (get-in db [user-id :orgs])))

(reg-event-db
 :users/set-orgs!
 [trim-v]
 (fn [db [user-id orgs]]
   (assoc-in db [user-id :orgs] orgs)))

(defn get-user-orgs! [user-id]
  (let [retrieving-orgs? (r/cursor state [:retrieving-orgs?])
        orgs (r/cursor state [:orgs])
        error-message (r/cursor state [:retrieving-orgs-error-message])]
    (reset! retrieving-orgs? true)
    (GET (str "/api/user/" user-id "/orgs")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-orgs? false)
                     (dispatch [:users/set-orgs! user-id (-> response :result :orgs)]))
          :error-handler (fn [error-response]
                           (.log js/console (clj->js error-response))
                           (reset! retrieving-orgs? false)
                           (reset! error-message (get-in error-response [:response :error :message])))})))


(defn UserOrganization [{:keys [id group-name]}]
  [:div {:id (str "org-" id)
         :class "user-org-entry"
         :style {:margin-bottom "1em"}}
   [:a {:href "#" :on-click (wrap-prevent-default #(do (dispatch [:set-current-org! id])
                                                       (nav-scroll-top (str "/org/" id "/users"))))}
    group-name]
   [Divider]])

(defn UserOrgs [user-id]
  (let [orgs (subscribe [:users/orgs user-id])]
    (r/create-class
     {:reagent-render (fn [this]
                        (when (seq @orgs)
                          [Segment
                           [Header {:as "h4" :dividing true} "Organizations"]
                           [:div {:id "user-organizations"}
                            (doall (for [org @orgs] ^{:key (:id org)}
                                     [UserOrganization org]))]]))
      :component-did-mount (fn [this]
                             (get-user-orgs! user-id))})))

(defn Orgs
  [{:keys [user-id]}]
  (r/create-class
   {:reagent-render
    (fn [this]
      [:div
       (when @(subscribe [:users/is-path-user-id-self?])
         [CreateOrg])
       [UserOrgs user-id]])}))
