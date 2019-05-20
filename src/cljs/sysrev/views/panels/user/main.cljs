(ns sysrev.views.panels.user.main
  (:require [clojure.string :as str]
            [ajax.core :refer [GET PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :refer [with-tooltip selection-dropdown]]
            [sysrev.views.semantic :refer [Segment Header Grid Row Column Radio Message MessageHeader Icon]]
            [sysrev.views.panels.orgs :refer [CreateOrg]]
            [sysrev.views.panels.project.support :refer [UserSupportSubscriptions]]
            [sysrev.views.panels.user.billing :refer [Billing]]
            [sysrev.views.panels.user.compensation :refer [PaymentsOwed PaymentsPaid]]
            [sysrev.views.panels.user.invitations :refer [Invitations]]
            [sysrev.views.panels.user.email :refer [EmailSettings VerifyEmail]]
            [sysrev.views.panels.user.orgs :refer [Orgs]]
            [sysrev.views.panels.user.profile :refer [Profile]]
            [sysrev.views.panels.user.projects :refer [UserProjects]]
            [sysrev.base]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.util :refer [full-size? get-url-path mobile?]]))

(def ^:private panel [:user-main])

(def initial-state {})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(reg-sub :users/path-user-id
         (fn [db _]
           (-> (re-find #"/user/(\d*)/*" @sysrev.base/active-route) second js/parseInt)))

(reg-sub :users/is-path-user-id-self?
         (fn [db _]
           (= @(subscribe [:self/user-id])
              @(subscribe [:users/path-user-id]))))
;;;
;;; TODO: refactor to remove this inputs/values/... stuff
;;;

(defn- parse-input [skey input]
  (case skey
    :ui-theme input
    nil))

(defn editing? []
  (= panel @(subscribe [:active-panel])))

(defn saved-values [& [skey]]
  (cond-> @(subscribe [:self/settings])
    skey (get skey)))

(defn active-values [& [skey]]
  (cond-> (:active-values @state)
    skey (get skey)))

(defn current-values [& [skey]]
  (let [active (active-values)]
    (cond-> (saved-values)
      (editing?) (merge active)
      skey (get skey))))

(defn active-inputs [& [skey]]
  (cond-> (:active-inputs @state)
    skey (get skey)))

(defn reset-fields []
  (let [values (r/cursor state [:active-values])
        inputs (r/cursor state [:active-inputs])]
    (reset! values {})
    (reset! inputs {})))

(defn valid-input? [& [skey]]
  (let [inputs (active-inputs)
        values (current-values)]
    (letfn [(valid? [skey]
              (if-let [input (get inputs skey)]
                (= (parse-input skey input)
                   (get values skey))
                true))]
      (if skey
        (valid? skey)
        (every? valid? (keys inputs))))))

(defn edit-setting [skey input]
  (let [inputs (r/cursor state [:active-inputs])
        values (r/cursor state [:active-values])
        value (parse-input skey input)]
    (swap! inputs assoc skey input)
    (when value
      (swap! values assoc skey value))))

(defn modified? []
  (not= (saved-values) (current-values)))

(defn save-changes []
  (let [values (current-values)
        saved (saved-values)
        changed-keys (filter #(not= (get values %)
                                    (get saved %))
                             (keys values))
        changes (mapv (fn [skey]
                        {:setting skey
                         :value (get values skey)})
                      changed-keys)]
    (dispatch [:action [:user/change-settings changes]])))

(defn- render-setting [skey]
  (if-let [input (active-inputs skey)]
    input
    (let [value (current-values skey)]
      (case skey
        :ui-theme (if (nil? value) "Default" value)
        nil))))

(defn- theme-selector []
  (let [active-theme (render-setting :ui-theme)]
    [selection-dropdown
     [:div.text active-theme]
     (->> ["Default" "Dark"]
          (mapv
           (fn [theme-name]
             [:div.item
              (into {:key theme-name
                     :on-click #(edit-setting :ui-theme theme-name)}
                    (when (= theme-name active-theme)
                      {:class "active selected"}))
              theme-name])))]))

(defn- user-options-box []
  (let [values (current-values)
        saved (saved-values)
        modified? (modified?)
        valid? (valid-input?)
        field-class #(if (valid-input? %) "" "error")]
    [:div.ui.segment.user-options
     [:h4.ui.dividing.header "Options"]
     [:div.ui.unstackable.form {:class (if valid? "" "warning")}
      (let [skey :ui-theme]
        [:div.fields
         [:div.eight.wide.field {:class (field-class skey)}
          [:label "Web Theme"]
          [theme-selector]]])]
     [:div
      [:div.ui.divider]
      [:div
       [:button.ui.primary.button
        {:class (if (and valid? modified?) "" "disabled")
         :on-click #(save-changes)}
        "Save changes"]
       [:button.ui.button
        {:class (if modified? "" "disabled")
         :on-click #(reset-fields)}
        "Reset"]]]]))

(defn- user-dev-tools-box []
  (let [user-id @(subscribe [:self/user-id])]
    (when @(subscribe [:user/admin?])
      [:div.ui.segment
       [:h4.ui.dividing.header "Dev Tools"]
       [:div
        ;; TODO: add method for deleting dev user labels
        #_ [:button.ui.yellow.button
            {:on-click
             #(do (dispatch [:action [:user/delete-member-labels user-id]])
                  (nav-scroll-top "/"))}
            "Delete Member Labels"]
        [:button.ui.orange.button
         {:on-click
          #(dispatch [:action [:user/delete-account user-id]])}
         "Delete Account"]]])))

(defn PublicReviewerOptIn
  "Public Reviewer Opt-In"
  []
  (let [active? (r/atom nil)
        loading? (r/atom true)
        user-id @(subscribe [:self/user-id])
        error-message (r/atom "")
        ;; TODO: get this value from identity request instead (way
        ;; easier, available everywhere)
        get-opt-in (fn []
                     (reset! loading? true)
                     (GET (str "/api/user/" user-id "/groups/public-reviewer/active")
                          {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                           :handler (fn [response]
                                      (reset! active? (-> response :result :active))
                                      (reset! loading? false))
                           :error-handler (fn [error-response]
                                            (reset! loading? false)
                                            (reset! error-message "There was an error retrieving opt-in status"))}))
        put-opt-in! (fn []
                      (reset! loading? true)
                      (PUT (str "/api/user/" user-id "/groups/public-reviewer/active")
                           {:params {:active (not @active?)}
                            :format :transit
                            :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                            :handler (fn [response]
                                       (reset! active? (-> response :result :active))
                                       (reset! loading? false))
                            :error-handler (fn [error-message]
                                             (reset! loading? false)
                                             (reset! error-message "There was an error when setting opt-in status"))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [verified @(subscribe [:self/verified])]
          (when-not (nil? @active?)
            [Segment
             [Header {:as "h4" :dividing true}
              "Public Reviewer Opt In"]
             (when-not verified
               [Message {:warning true}
                [:a {:href "/user/settings/email"}
                 "Your email address is not yet verified."]])
             [Radio {:toggle true
                     :id "opt-in-public-reviewer"
                     :label "Publicly Listed as a Paid Reviewer"
                     :checked @active?
                     :disabled (or (not verified) @loading?)
                     :on-change (fn [e] (put-opt-in!))}]
             (when-not (str/blank? @error-message)
               [Message {:onDismiss #(reset! error-message nil)
                         :negative true}
                [MessageHeader "Opt-In Error"]
                @error-message])])))
      :get-initial-state
      (fn [this]
        (reset! loading? true)
        (get-opt-in))})))

(defn UserContent
  []
  (let [current-path sysrev.base/active-route
        current-panel (subscribe [:active-panel])
        payments-owed (subscribe [:compensation/payments-owed])
        payments-paid (subscribe [:compensation/payments-paid])
        invitations (subscribe [:user/invitations])
        active-item (fn [current-path sub-path]
                      (cond-> "item "
                        (re-matches (re-pattern (str ".*" sub-path)) current-path) (str " active")))
        uri-fn (fn [sub-path]
                 (str "/user/" @(subscribe [:users/path-user-id]) sub-path))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:nav
          #_[:div.ui.top.attached.middle.aligned.segment.desktop
             [:h4.ui.header.title-header "Personal Settings"]]
          [:div.ui.secondary.pointing.menu.primary-menu.bottom.attached
           {:class (str " " (if (mobile?) "tiny"))}
           [:a {:key "#profile"
                :id "user-profile"
                :class (active-item @current-path "/profile")
                :href (uri-fn "/profile")}
            "Profile"]
           (when @(subscribe [:users/is-path-user-id-self?])
             [:a {:key "#general"
                  :id "user-general"
                  :class (active-item @current-path "/settings")
                  :href (uri-fn "/settings")}
              "General"])
           ;; should check if projects exist
           [:a {:key "#projects"
                :id "user-projects"
                :class (active-item @current-path "/projects")
                :href (uri-fn "/projects")}
            "Projects"]
           ;; should check if orgs exist
           [:a {:keys "#user-orgs"
                :id "user-orgs"
                :class (active-item @current-path "/orgs")
                :href (uri-fn "/orgs")}
            "Organizations"]
           (when @(subscribe [:users/is-path-user-id-self?])
             [:a {:key "#billing"
                  :id "user-billing"
                  :class (active-item @current-path "/billing")
                  :href (uri-fn "/billing")}
              "Billing"])
           (when @(subscribe [:users/is-path-user-id-self?])
             [:a {:key "#email"
                  :id "user-email"
                  :class (active-item @current-path "/email")
                  :href (uri-fn "/email")}
              "Email"])
           (when @(subscribe [:users/is-path-user-id-self?])
             (when-not (empty? (or @payments-owed @payments-paid))
               [:a {:key "#compensation"
                    :id "user-compensation"
                    :class (active-item @current-path "/compensation")
                    :href (uri-fn "/compensation")}
                "Compensation"]))
           (when @(subscribe [:users/is-path-user-id-self?])
             (when-not (empty? @invitations)
               [:a {:key "#invitations"
                    :id "user-invitations"
                    :class (active-item @current-path "/invitations")
                    :href (uri-fn "/invitations")}
                "Invitations" (when-not (empty? (filter #(nil? (:accepted (val %))) @invitations))
                                [Icon {:name "circle"
                                       :size "tiny"
                                       :color "red"
                                       :style {:margin-left "0.5em"}}])]))]]
         [:div#user-content
          (condp re-matches @current-path
            #"/user/(\d*)/profile"
            [Profile {:user-id @(subscribe [:users/path-user-id])}]
            #"/user/(\d*)/settings" ;; general
            [:div
             [Grid {:stackable true}
              [Row
               [Column {:width 8} [user-options-box]]
               [Column {:width 8}
                [user-dev-tools-box]]]
              [Row
               [Column {:width 8}
                [PublicReviewerOptIn]
                [CreateOrg]]]]]
            #"/user/(\d*)/projects"
            [UserProjects {:user-id @(subscribe [:users/path-user-id])}]
            #"/user/(\d*)/orgs"
            [Orgs {:user-id @(subscribe [:users/path-user-id])}]
            #"/user/(\d*)/billing"
            [Billing]
            #"/user/(\d*)/email"
            [EmailSettings]
            #"/user/(\d*)/compensation"
            [:div
             [PaymentsOwed]
             [PaymentsPaid]
             [UserSupportSubscriptions]]
            #"/user/(\d*)/invitations"
            [Invitations]
            #"/user/(\d*)/email/(\w+)" :>>
            (fn [[_ user-id code]] [VerifyEmail code])
            ;; default before the active panel is loaded
            ;; and this component still exists
            [:div {:style {:display "none"}}])]])
      :get-initial-state
      (fn [this]
        (dispatch [:compensation/get-payments-owed!])
        (dispatch [:compensation/get-payments-paid!])
        (dispatch [:user/get-invitations!]))})))

(defmethod logged-out-content [:user-main] []
  (logged-out-content :logged-out))

(defmethod panel-content [:user-main] []
  (fn [child]
    [UserContent]))
