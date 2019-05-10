(ns sysrev.views.panels.user.orgs
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.util :as util :refer [wrap-prevent-default]]
            [sysrev.views.panels.orgs :refer [CreateOrg]]
            [sysrev.views.semantic :refer [Segment Header Divider]]
            [sysrev.views.panels.user.profile :as user-profile])
  (:require-macros [reagent.interop :refer [$]]))

(def panel user-profile/panel)

(def state (r/cursor app-db [:state :panel panel]))

(defn UserOrganization [{:keys [id group-name]}]
  [:div {:id (str "org-" id)
         :class "user-org-entry"
         :style {:margin-bottom "1em"}}
   [:a {:href "#" :on-click (wrap-prevent-default #(do (dispatch [:set-current-org! id])
                                                       (nav-scroll-top "/org/users")))}
    group-name]
   [Divider]])

(defn UserOrgs []
  (let [orgs (subscribe [:orgs])]
    (r/create-class
     {:reagent-render (fn [this]
                        (when (seq @orgs)
                          [Segment
                           [Header {:as "h4" :dividing true} "Organizations"]
                           [:div {:id "user-organizations"}
                            (doall (for [org @orgs] ^{:key (:id org)}
                                     [UserOrganization org]))]]))
      :component-did-mount (fn [this]
                             (dispatch [:read-orgs!]))})))

;; this SHOULD take user-id and orgs should be retrievable for a user
;; org code should be refactored like user code
(defn Orgs
  [{:keys [user-id username]}]
  (r/create-class
   {:reagent-render
    (fn [this]
      [:div
       (when @(subscribe [:users/is-path-user-id-self?])
         [CreateOrg])
       [UserOrgs]])}))
