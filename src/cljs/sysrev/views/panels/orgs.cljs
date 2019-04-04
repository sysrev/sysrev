(ns sysrev.views.panels.orgs
  (:require [ajax.core :refer [POST]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.semantic :refer [Form FormField FormInput Button Segment Header Input Message MessageHeader]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:orgs])

(def state (r/cursor app-db [:state :panel panel]))

(defn create-org!
  [org-name]
  (let [create-org-retrieving? (r/cursor state [:create-org-retrieving?])
        create-org-error (r/cursor state [:create-org-error])]
    (reset! create-org-retrieving? true)
    (POST "/api/org"
          {:params {:org-name org-name}
           :headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :handler (fn [response]
                      (reset! create-org-retrieving? false)
                      (dispatch [:set-current-org! (get-in response [:result :id])])
                      (nav-scroll-top "/org/users"))
           :error-handler (fn [error-response]
                            (reset! create-org-retrieving? false)
                            (reset! create-org-error
                                    (get-in error-response [:response :error :message])))})))

(defn CreateOrgForm
  []
  (let [new-org (r/cursor state [:new-org])
        create-org-error (r/cursor state [:create-org-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [Form {:on-submit #(create-org! @new-org)}
          [FormField
           [Input {:placeholder "Organization Name"
                   :value @new-org
                   :action (r/as-element [Button {:primary true
                                                  :class "create-organization"} "Create"])
                   :on-change #(reset! new-org
                                       (-> ($ % :target.value)))}]]]
         (when-not (empty? @create-org-error)
           [Message {:negative true
                     :onDismiss #(reset! create-org-error nil)}
            [MessageHeader {:as "h4"} "Create Organization Error"]
            @create-org-error])])
      :get-initial-state
      (fn [this]
        (reset! new-org "")
        {})
      :component-did-mount
      (fn [this]
        (reset! create-org-error nil))})))

(defn CreateOrg
  []
  [Segment {:secondary true}
   [Header {:as "h4"
            :dividing true} "Create a New Organization"]
   [CreateOrgForm]])
