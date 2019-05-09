(ns sysrev.views.panels.user.orgs
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.panels.orgs :refer [CreateOrg]]
            [sysrev.views.semantic :refer [Segment Header Divider]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:state :panels :user :profile])

(def state (r/cursor app-db [:state :panel panel]))

(defn UserOrganization
  [{:keys [id group-name]}]
  [:div {:style {:margin-bottom "1em"}
         :id (str "org-" id)}
   [:a {:href "#"
        :on-click (fn [e]
                    ($ e preventDefault)
                    (dispatch [:set-current-org! id])
                    (nav-scroll-top "/org/users"))}
    group-name]
   [Divider]])

(defn UserOrgs
  []
  (let [orgs (subscribe [:orgs])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when-not (empty? @orgs)
          [Segment
           [Header {:as "h4"
                    :dividing true}
            "Organizations"]
           [:div {:id "user-organizations"}
            (map (fn [org]
                   ^{:key (:id org)}
                   [UserOrganization org])
                 @orgs)]]))
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
