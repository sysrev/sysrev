(ns sysrev.views.panels.project.settings
  (:require [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :refer
             [with-tooltip wrap-dropdown selection-dropdown]]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.shared.util :refer [parse-integer in?]]))

(def ^:private panel [:project :project :settings])

(def initial-state {:confirming? false})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn- parse-input [skey input]
  (case skey
    :second-review-prob
    (let [n (parse-integer input)]
      (when (and (int? n) (>= n 0) (<= n 100))
        (* n 0.01)))

    :public-access input

    nil))

(defn admin? []
  (or @(subscribe [:member/admin?])
      @(subscribe [:user/admin?])))

(defn editing? []
  (and (admin?) (= panel @(subscribe [:active-panel]))))

(defn saved-values [& [skey]]
  (cond-> @(subscribe [:project/settings])
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
    (when-not (nil? value)
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
                      changed-keys)
        project-id @(subscribe [:active-project-id])]
    (dispatch [:action [:project/change-settings project-id changes]])))

(defn- render-setting [skey]
  (if-let [input (active-inputs skey)]
    input
    (when-let [value (current-values skey)]
      (case skey
        :second-review-prob
        (when (float? value)
          (str (int (+ 0.5 (* value 100)))))

        :public-access value

        nil))))

(def-action :project/delete
  :uri (fn [] "/api/delete-project")
  :content (fn [project-id] {:project-id project-id})
  :process
  (fn [{:keys [db]} _ result]
    {:db (-> db
             (assoc-in [:state :active-project-id] nil))
     :dispatch-n (list [:navigate [:select-project]]
                       [:fetch [:identity]])}))

(defn input-field-class [skey]
  (if (valid-input? skey) "" "error"))

(def review-priority-buttons
  [{:key :single
    :label "Single"
    :value 0
    :tooltip "Prioritize single-user review of articles"}
   {:key :balanced
    :label "Balanced"
    :value 50
    :tooltip "Assign mix of unreviewed and partially-reviewed articles"}
   {:key :full
    :label "Full"
    :value 100
    :tooltip "Prioritize fully reviewed articles"}])

(defn ReviewPriorityButtonTooltip [{:keys [key tooltip]}]
  (let [tooltip-key (str "review-priority--" (name key))]
    [:div.ui.flowing.popup.transition.hidden.tooltip
     {:id tooltip-key}
     [:p tooltip]]))

(defn ReviewPriorityButton [{:keys [key label value tooltip]}]
  (let [skey :second-review-prob
        active-value (int (render-setting skey))
        active? (= value active-value)
        tooltip-key (str "review-priority--" (name key))]
    [with-tooltip
     [:button.ui.button
      {:class (if active? "active" "")
       :on-click #(edit-setting skey value)}
      label]
     {:inline false
      :popup (str "#" tooltip-key)}]))

(defn DoubleReviewPriorityField []
  (let [skey :second-review-prob]
    [:div.field {:class (input-field-class skey)}
     [:label "Article Review Priority"]
     [:div.ui.buttons
      (doall
       (for [entry review-priority-buttons]
         ^{:key (:key entry)}
         [ReviewPriorityButton entry]))]
     (doall
      (for [entry review-priority-buttons]
        ^{:key [:tooltip (:key entry)]}
        [ReviewPriorityButtonTooltip entry]))]))

(def public-access-buttons
  [{:key :public
    :label [:span #_ [:i.globe.icon] "Public"]
    :value true
    :tooltip "Allow anyone to view project"}
   {:key :private
    :label [:span #_ [:i.lock.icon] "Private"]
    :value false
    :tooltip "Allow access only for project members"}])

(defn PublicAccessButtonTooltip [{:keys [key tooltip]}]
  (let [tooltip-key (str "public-access--" (name key))]
    [:div.ui.flowing.popup.transition.hidden.tooltip
     {:id tooltip-key}
     [:p tooltip]]))

(defn PublicAccessButton [{:keys [key label value tooltip]}]
  (let [skey :public-access
        active-value (or (current-values skey) false)
        active? (= value active-value)
        tooltip-key (str "public-access--" (name key))]
    [with-tooltip
     [:button.ui.button
      {:class (if active? "active" "")
       :on-click #(edit-setting skey value)}
      label]
     {:inline false
      :popup (str "#" tooltip-key)}]))

(defn PublicAccessField []
  (let [skey :public-access]
    [:div.field {:class (input-field-class skey)}
     [:label "Project Visibility"]
     [:div.ui.buttons
      (doall
       (for [entry public-access-buttons]
         ^{:key (:key entry)}
         [PublicAccessButton entry]))]
     (doall
      (for [entry public-access-buttons]
        ^{:key [:tooltip (:key entry)]}
        [PublicAccessButtonTooltip entry]))]))

(defn- project-options-box []
  (let [admin? (admin?)
        values (current-values)
        saved (saved-values)
        modified? (modified?)
        valid? (valid-input?)
        field-class #(if (valid-input? %) "" "error")]
    [:div.ui.segment
     [:h4.ui.dividing.header "Configuration options"]
     [:div.ui.form {:class (if valid? "" "warning")}
      [PublicAccessField]
      [DoubleReviewPriorityField]]
     (when admin?
       [:div
        [:div.ui.divider]
        [:div
         (let [enabled? (and valid? modified?)]
           [:button.ui.right.labeled.positive.icon.button
            {:class (if enabled? "" "disabled")
             :on-click #(when enabled? (save-changes))}
            "Save Changes"
            [:i.check.circle.outline.icon]])
         [:button.ui.right.labeled.icon.button
          {:class (if modified? "" "disabled")
           :on-click #(when modified? (reset-fields))}
          "Reset"
          [:i.cancel.icon]]]])]))

(defonce members-state (r/cursor state [:members]))

(defn- ProjectMembersList []
  (let [owner? false
        all-perms (if owner? ["admin" "resolve"] ["resolve"])
        user-ids @(subscribe [:project/member-user-ids nil true])]
    [:div
     [:h4.ui.dividing.header "Members"]
     [:div.ui.relaxed.divided.list
      (doall
       (for [user-id user-ids]
         [:div.item {:key user-id}
          [:div.right.floated.content
           (let [permissions @(subscribe [:member/permissions user-id])]
             (doall
              (for [perm permissions]
                [:div.ui.small.label {:key perm} perm])))]
          [:div.content
           {:style {:padding-top "4px"
                    :padding-bottom "4px"}}
           [:i.user.icon]
           @(subscribe [:user/display user-id])]]))]]))

(defn- UserSelectDropdown []
  (let [{:keys [selected-user]} @members-state
        user-ids @(subscribe [:project/member-user-ids nil true])]
    [selection-dropdown
     [:div.default.text "User"]
     (->> user-ids
          (mapv
           (fn [user-id]
             [:div.item
              (into {:key (or user-id "none")
                     :data-value (if user-id (str user-id) "none")}
                    (when (= user-id selected-user)
                      {:class "active selected"}))
              [:span [:i.user.icon] @(subscribe [:user/display user-id])]])))
     {:class "ui fluid search selection dropdown"
      :onChange
      (fn [value text item]
        (let [user-id (parse-integer value)]
          (swap! members-state
                 assoc :selected-user user-id)))}]))

(def permission-values ["admin"])

(defn- MemberPermissionDropdown []
  (let [{:keys [selected-user selected-permission]} @members-state]
    [selection-dropdown
     [:div.default.text "Permission"]
     (->> permission-values
          (mapv
           (fn [perm]
             [:div.item
              (into {:key (or perm "none")
                     :data-value (if perm perm "none")}
                    (when (= perm selected-permission)
                      {:class "active selected"}))
              [:span [:i.key.icon] perm]])))
     {:class "ui fluid search selection dropdown"
      :onChange
      (fn [value text item]
        (let [perm (if (in? ["" "none"] value) nil value)]
          (swap! members-state
                 assoc :selected-permission perm)))}]))

(defn- ProjectPermissionsForm []
  (when (admin?)
    [:div
     [:h4.ui.dividing.header {:style {:margin-top "1em"}}
      "Manage Permissions"]
     [:div.ui.form.project-permissions
      [:div.fields
       [:div.four.wide.field.user-select
        [UserSelectDropdown]]
       [:div.four.wide.field.permission-select
        [MemberPermissionDropdown]]
       [:div.three.wide.field
        [:div.ui.fluid.buttons
         [:button.ui.icon.button.disabled [:i.plus.circle.icon]]
         [:button.ui.icon.button.disabled [:i.minus.circle.icon]]]]
       [:div.one.wide.field]
       [:div.four.wide.field
        {:style {:text-align "right"}}
        [:button.ui.icon.button
         {:on-click
          #(-> (js/$ ".project-settings .ui.selection.dropdown")
               (.dropdown "clear"))}
         [:i.cancel.icon]]]]]]))

(defn- ProjectMembersBox []
  [:div.ui.segment
   [ProjectMembersList]
   #_ [ProjectPermissionsForm]])

(defn ConfirmationAlert
  "An alert for confirming or cancelling an action.
  props is
  {
  :cancel-on-click      fn  ; user clicks cancel, same fn used for dismissing
                            ; alert
  :confirm-on-click     fn  ; user clicks confirm
  :confirmation-message fn  ; fn that returns a reagent component
  }"
  [props]
  (fn [{:keys [cancel-on-click confirm-on-click
               confirmation-message]} props]
    [:div
     [:div
      [confirmation-message]]
     [:br]
     [:div
      [:button {:type "button"
                :class "ui button"
                :on-click confirm-on-click}
       "Yes"]
      [:button {:type "button"
                :class "ui button primary"
                :on-click cancel-on-click}
       "No"]]]))

;; TODO: do not render this unless project can be deleted (i.e. no labels);
;; currently allows clicking this and then fails server-side
(defn DeleteProject
  "Delete a project"
  []
  (let [confirming? (r/cursor state [:confirming?])
        active-project-id (subscribe [:active-project-id])]
    [:div.ui.segment
     [:h4.ui.dividing.header "Delete Project"]
     [:div.ui.relaxed.divided.list
      (when @confirming?
        [ConfirmationAlert
         {:cancel-on-click #(reset! confirming? false)
          :confirm-on-click
          (fn []
            (reset! confirming? false)
            (dispatch [:action [:project/delete @active-project-id]]))
          :confirmation-message
          (fn []
            [:div.ui.red.header
             [:h3 "Warning: All sources and labeling will be lost!"
              [:br]
              " Are you sure you want to delete this project?"]])}])
      (when-not @confirming?
        [:button.ui.button
         {:on-click
          #(reset! confirming? true)}
         "Delete this Project"])]]))

(defmethod panel-content [:project :project :settings] []
  (fn [child]
    (ensure-state)
    (let [user-id @(subscribe [:self/user-id])
          admin? (admin?)]
      [:div.project-content
       [ReadOnlyMessage
        "Changing settings is restricted to project administrators."]
       [:div.ui.two.column.stackable.grid.project-settings
        [:div.ui.row
         [:div.ui.column
          [project-options-box]]
         [:div.ui.column
          [ProjectMembersBox]
          (when @(subscribe [:member/admin?])
            [DeleteProject])]]]])))
