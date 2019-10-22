(ns sysrev.views.panels.orgs
  (:require [ajax.core :refer [POST]]
            [goog.uri.utils :as uri-utils]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$]]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.base :refer [active-route]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer
             [Form FormField FormInput Button Segment Header Input Message MessageHeader]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute]]))

(setup-panel-state panel [:orgs] {:state-var state})

(defn create-org! [org-name & {:keys [redirect-subpath]
                               :or {redirect-subpath "/users"}}]
  (let [create-org-retrieving? (r/cursor state [:create-org-retrieving?])
        create-org-error (r/cursor state [:create-org-error])]
    (when-not @create-org-retrieving?
      (reset! create-org-retrieving? true)
      (POST "/api/org"
            {:params {:org-name org-name}
             :headers {"x-csrf-token" @(subscribe [:csrf-token])}
             :handler (fn [{:keys [result]}]
                        (let [new-org-id (:id result)]
                          (reset! create-org-retrieving? false)
                          (nav-scroll-top (str "/org/" new-org-id redirect-subpath))))
             :error-handler (fn [resp]
                              (reset! create-org-retrieving? false)
                              (reset! create-org-error (-> resp :response :error :message)))}))))

(defn CreateOrgForm []
  (let [new-org (r/cursor state [:new-org])
        create-org-error (r/cursor state [:create-org-error])
        create-org-retrieving? (r/cursor state [:create-org-retrieving?])
        panel-type (uri-utils/getParamValue @active-route "type")]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [Form {:on-submit #(if (or (= panel-type "new-account")
                                    (= panel-type "existing-account"))
                              (create-org! @new-org :redirect-subpath "/plans" )
                              (create-org! @new-org))
                :loading @create-org-retrieving?}
          [FormField
           [Input {:placeholder "Organization Name"
                   :id "create-org-input"
                   :value @new-org
                   :action (r/as-element [Button {:primary true
                                                  :class "create-organization"
                                                  :id "create-org-button"} "Create"])
                   :on-change (fn [e]
                                (reset! create-org-error nil)
                                (reset! new-org
                                        (-> ($ e :target.value))))}]]]
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

(defn CreateOrg []
  [Segment {:secondary true}
   [Header {:as "h4" :dividing true} "Create a New Organization"]
   [CreateOrgForm]])

(defn CreateOrgPanel
  []
  (let [current-path (uri-utils/getPath @active-route)
        panel-type (uri-utils/getParamValue @active-route "type")]
    [:div
     (when (= panel-type "new-account")
       [:h4 {:style {:text-align "center"}} "Next, create a Sysrev organization for your team"])
     (when (= panel-type "existing-account")
       [:h4 {:style {:text-align "center"}} "First, create a Sysrev organization for your team"])
     [Segment {:secondary true
               :class "ui segment auto-margin auth-segment"}
      [Header {:as "h4" :dividing true} "Create a New Organization"]
      [CreateOrgForm]]]))

(defmethod panel-content panel []
  (fn [child]
    [CreateOrgPanel]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute
 create-org "/create/org" []
 (dispatch [:set-active-panel [:orgs]]))

