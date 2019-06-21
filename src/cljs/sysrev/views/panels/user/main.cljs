(ns sysrev.views.panels.user.main
  (:require [clojure.string :as str]
            [ajax.core :refer [GET PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
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
            [sysrev.base :refer [active-route]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.util :refer [full-size? get-url-path mobile?]]
            [sysrev.shared.util :as sutil :refer [parse-integer css]])
  (:require-macros [sysrev.macros :refer [setup-panel-state]]))

(setup-panel-state panel [:user-main] {:state-var state})

(defn user-id-from-url []
  (-> (re-find #"/user/(\d*)/*" @active-route) second parse-integer))

(reg-sub :users/path-user-id (fn [db] (user-id-from-url)))

(reg-sub :users/is-path-user-id-self?
         :<- [:self/user-id]
         :<- [:users/path-user-id]
         (fn [[self-id path-id]]
           (= self-id path-id)))

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
        get-opt-in
        (fn []
          (reset! loading? true)
          (GET (str "/api/user/" user-id "/groups/public-reviewer/active")
               {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                :handler (fn [{:keys [result]}]
                           (reset! active? (:enabled result))
                           (reset! loading? false))
                :error-handler (fn [_]
                                 (reset! loading? false)
                                 (reset! error-message "There was an error retrieving opt-in status"))}))
        put-opt-in!
        (fn []
          (reset! loading? true)
          (PUT (str "/api/user/" user-id "/groups/public-reviewer/active")
               {:params {:enabled (not @active?)}
                :format :transit
                :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                :handler (fn [{:keys [result]}]
                           (reset! active? (:enabled result))
                           (reset! loading? false))
                :error-handler (fn [_]
                                 (reset! loading? false)
                                 (reset! error-message "There was an error when setting opt-in status"))}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [verified @(subscribe [:self/verified])]
          (when-not (nil? @active?)
            [Segment
             [Header {:as "h4" :dividing true} "Public Reviewer Opt In"]
             (when-not verified
               [Message {:warning true}
                [:a {:href (str "/user/" @(subscribe [:self/user-id]) "/email")}
                 "Your email address is not yet verified."]])
             [Radio {:toggle true
                     :id "opt-in-public-reviewer"
                     :label "Publicly Listed as a Paid Reviewer"
                     :checked @active?
                     :disabled (or (not verified) @loading?)
                     :on-change (fn [e] (put-opt-in!))}]
             (when-not (str/blank? @error-message)
               [Message {:negative true, :onDismiss #(reset! error-message nil)}
                [MessageHeader "Opt-In Error"]
                @error-message])])))
      :get-initial-state
      (fn [this]
        (reset! loading? true)
        (get-opt-in))})))

(defn UserContent []
  (let [self-id (subscribe [:self/user-id])
        path-id (subscribe [:users/path-user-id])
        self? (subscribe [:users/is-path-user-id-self?])
        current-panel (subscribe [:active-panel])
        payments-owed (subscribe [:compensation/payments-owed])
        payments-paid (subscribe [:compensation/payments-paid])
        invitations (subscribe [:user/invitations])
        item-class (fn [sub-path]
                     (css "item" [(re-matches (re-pattern (str ".*" sub-path)) @active-route)
                                  "active"]))
        uri-fn (fn [sub-path]
                 (str "/user/" @path-id sub-path))]
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
                :class (item-class "/profile")
                :href (uri-fn "/profile")}
            "Profile"]
           (when @self?
             [:a {:key "#general"
                  :id "user-general"
                  :class (item-class "/settings")
                  :href (uri-fn "/settings")}
              "General"])
           ;; should check if projects exist
           [:a {:key "#projects"
                :id "user-projects"
                :class (item-class "/projects")
                :href (uri-fn "/projects")}
            "Projects"]
           ;; should check if orgs exist
           [:a {:keys "#user-orgs"
                :id "user-orgs"
                :class (item-class "/orgs")
                :href (uri-fn "/orgs")}
            "Organizations"]
           (when @self?
             [:a {:key "#billing"
                  :id "user-billing"
                  :class (item-class "/billing")
                  :href (uri-fn "/billing")}
              "Billing"])
           (when @self?
             [:a {:key "#email"
                  :id "user-email"
                  :class (item-class "/email")
                  :href (uri-fn "/email")}
              "Email"])
           (when @self?
             (when-not (empty? (or @payments-owed @payments-paid))
               [:a {:key "#compensation"
                    :id "user-compensation"
                    :class (item-class "/compensation")
                    :href (uri-fn "/compensation")}
                "Compensation"]))
           (when @self?
             (when-not (empty? @invitations)
               [:a {:key "#invitations"
                    :id "user-invitations"
                    :class (item-class "/invitations")
                    :href (uri-fn "/invitations")}
                "Invitations" (when-not (empty? (filter #(nil? (:accepted (val %))) @invitations))
                                [Icon {:name "circle"
                                       :size "tiny"
                                       :color "red"
                                       :style {:margin-left "0.5em"}}])]))]]
         [:div#user-content
          (condp re-matches @active-route
            #"/user/(\d*)/profile"
            [Profile {:user-id @path-id}]
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
            [UserProjects {:user-id @path-id}]
            #"/user/(\d*)/orgs"
            [Orgs {:user-id @path-id}]
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
      :component-did-mount
      (fn [this]
        (dispatch [:compensation/get-payments-owed!])
        (dispatch [:compensation/get-payments-paid!])
        (dispatch [:user/get-invitations!]))})))

;; this is only when the direct link to /user/<uid>/profile is called,
;; otherwise, the logged-out-content that is defined in
;; sysrev.views.panels.users is used
(defmethod logged-out-content [:user-main] []
  [UserContent])

(defmethod panel-content [:user-main] []
  (fn [child]
    [UserContent]))
