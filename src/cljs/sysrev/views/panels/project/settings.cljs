(ns sysrev.views.panels.project.settings
  (:require [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :refer
             [with-tooltip wrap-dropdown selection-dropdown
              SaveResetForm ConfirmationDialog]]
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

(def-action :project/change-settings
  :uri (fn [project-id changes] "/api/change-project-settings")
  :content (fn [project-id changes]
             {:project-id project-id :changes changes})
  :process (fn [{:keys [db]} [project-id _] {:keys [settings]}]
             {:db (assoc-in db [:data :project project-id :settings] settings)}))

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

(defn- input-field-class [skey]
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

(defn- ReviewPriorityButtonTooltip [{:keys [key tooltip]}]
  (let [tooltip-key (str "review-priority--" (name key))]
    [:div.ui.flowing.popup.transition.hidden.tooltip
     {:id tooltip-key}
     [:p tooltip]]))

(defn- ReviewPriorityButton [{:keys [key label value tooltip]}]
  (let [skey :second-review-prob
        active-value (int (render-setting skey))
        active? (= value active-value)
        tooltip-key (str "review-priority--" (name key))
        admin? (admin?)]
    [with-tooltip
     [:button.ui.button
      {:class (if active? "active" "")
       :on-click (if admin? #(edit-setting skey value) nil)}
      label]
     {:inline false
      :popup (str "#" tooltip-key)}]))

(defn- DoubleReviewPriorityField []
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

(defn- PublicAccessButtonTooltip [{:keys [key tooltip]}]
  (let [tooltip-key (str "public-access--" (name key))]
    [:div.ui.flowing.popup.transition.hidden.tooltip
     {:id tooltip-key}
     [:p tooltip]]))

(defn- PublicAccessButton [{:keys [key label value tooltip]}]
  (let [skey :public-access
        active-value (or (current-values skey) false)
        active? (= value active-value)
        tooltip-key (str "public-access--" (name key))
        admin? (admin?)]
    [with-tooltip
     [:button.ui.button
      {:class (if active? "active" "")
       :on-click (if admin? #(edit-setting skey value) nil)}
      label]
     {:inline false
      :popup (str "#" tooltip-key)}]))

(defn- PublicAccessField []
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

(defn- ProjectOptionsBox []
  (let [admin? (admin?)
        values (current-values)
        saved (saved-values)
        modified? (modified?)
        valid? (valid-input?)
        field-class #(if (valid-input? %) "" "error")]
    [:div.ui.segment
     [:h4.ui.dividing.header "Options"]
     [:div.ui.form {:class (if valid? "" "warning")}
      [PublicAccessField]
      [DoubleReviewPriorityField]]
     (when admin?
       [:div
        [:div.ui.divider]
        [SaveResetForm
         :can-save? (and valid? modified?)
         :can-reset? modified?
         :on-save #(save-changes)
         :on-reset #(reset-fields)
         :saving?
         (and modified?
              (loading/any-action-running? :only :project/change-settings))]])]))

(defonce members-state (r/cursor state [:members]))

(def-action :project/change-permissions
  :uri (fn [project-id users-map] "/api/change-project-permissions")
  :content (fn [project-id users-map]
             {:project-id project-id :users-map users-map})
  :process (fn [{:keys [db]} [project-id _] {:keys [success]}]
             (when success
               {:dispatch [:reload [:project project-id]]})))

(defn- all-project-user-ids []
  @(subscribe [:project/member-user-ids nil true]))

(defn- active-member-permissions [user-id]
  (get-in @members-state [:permissions user-id]))

(defn- saved-member-permissions [user-id]
  @(subscribe [:member/permissions user-id]))

(defn- current-member-permissions [user-id]
  (let [saved-perms (saved-member-permissions user-id)
        active-perms (active-member-permissions user-id)]
    (if active-perms active-perms saved-perms)))

(defn- edit-member-permissions [user-id new-perms]
  (swap! members-state assoc-in [:permissions user-id] new-perms))

(defn- add-member-permission [user-id perm]
  (let [current-perms (current-member-permissions user-id)]
    (swap! members-state assoc-in [:permissions user-id]
           (-> (concat current-perms [perm])
               distinct vec))))

(defn- remove-member-permission [user-id perm]
  (let [current-perms (current-member-permissions user-id)]
    (swap! members-state assoc-in [:permissions user-id]
           (-> (remove #(= perm %) current-perms)
               vec))))

(defn- all-saved-permissions []
  (->> (all-project-user-ids)
       (map (fn [user-id]
              {user-id @(subscribe [:member/permissions user-id])}))
       (apply merge)))

(defn- all-active-permissions []
  (->> (all-project-user-ids)
       (map (fn [user-id]
              (when-let [perms (active-member-permissions user-id)]
                {user-id perms})))
       (remove nil?)
       (apply merge)))

(defn- all-current-permissions []
  (merge (all-saved-permissions)
         (all-active-permissions)))

(defn- permissions-changed? []
  (not= (all-current-permissions)
        (all-saved-permissions)))

(defn- reset-permissions []
  (swap! members-state assoc :permissions nil))

(defn- reset-permissions-fields []
  (-> (js/$ ".project-settings .project-members .ui.selection.dropdown")
      (.dropdown "clear")))

(defn save-permissions []
  (let [project-id @(subscribe [:active-project-id])
        changes (all-active-permissions)]
    (when (permissions-changed?)
      (dispatch [:action [:project/change-permissions
                          project-id changes]]))))

(defn- ProjectMembersList []
  (let [owner? false
        all-perms (if owner? ["admin" "resolve"] ["resolve"])
        user-ids (all-project-user-ids)]
    [:div
     [:h4.ui.dividing.header "Members"]
     [:div.ui.relaxed.divided.list
      (doall
       (for [user-id user-ids]
         [:div.item {:key user-id}
          [:div.right.floated.content
           (let [saved (saved-member-permissions user-id)
                 current (current-member-permissions user-id)
                 permissions (distinct (concat saved current))]
             (doall
              (for [perm permissions]
                [:div.ui.small.label
                 {:class (cond (and (in? saved perm)
                                    (not (in? current perm)))
                               "orange disabled"

                               (and (in? current perm)
                                    (not (in? saved perm)))
                               "green"

                               :else nil)
                  :key perm}
                 perm])))]
          [:div.content
           {:style {:padding-top "4px"
                    :padding-bottom "4px"}}
           [:i.user.icon]
           @(subscribe [:user/display user-id])]]))]]))

(defn- UserSelectDropdown []
  (let [{:keys [selected-user]} @members-state
        user-ids (all-project-user-ids)]
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

(defn- AddPermissionButton []
  (let [{:keys [selected-user selected-permission]} @members-state
        member-permissions (when selected-user
                             (current-member-permissions selected-user))
        allow-add? (and selected-user selected-permission
                        (not (in? member-permissions selected-permission)))]
    [:button.ui.icon.button
     {:class (if allow-add? nil "disabled")
      :on-click (when allow-add?
                  #(add-member-permission selected-user selected-permission))}
     [:i.plus.circle.icon]]))

(defn- RemovePermissionButton []
  (let [{:keys [selected-user selected-permission]} @members-state
        member-permissions (when selected-user
                             (current-member-permissions selected-user))
        allow-remove? (and selected-user selected-permission
                           (in? member-permissions selected-permission))]
    [:button.ui.icon.button
     {:class (if allow-remove? nil "disabled")
      :on-click (when allow-remove?
                  #(remove-member-permission selected-user selected-permission))}
     [:i.minus.circle.icon]]))

(defn- ProjectPermissionsForm []
  (when (admin?)
    [:div
     [:h4.ui.dividing.header {:style {:margin-top "1em"}}
      "Manage Permissions"]
     [:div.ui.form.project-permissions
      [:div.fields
       [:div.six.wide.field.user-select
        [UserSelectDropdown]]
       [:div.five.wide.field.permission-select
        [MemberPermissionDropdown]]
       [:div.three.wide.field
        [:div.ui.fluid.buttons
         [AddPermissionButton]
         [RemovePermissionButton]]]
       [:div.two.wide.field
        {:style {:text-align "right"}}
        (let [{:keys [selected-user selected-permission]} @members-state
              fields-set? (not (and (nil? selected-user)
                                    (nil? selected-permission)))]
          [:button.ui.icon.button
           {:class (if fields-set? nil "disabled")
            :on-click #(reset-permissions-fields)}
           [:i.eraser.icon]])]]]
     [:div.ui.divider]
     (let [project-id @(subscribe [:active-project-id])
           changed? (permissions-changed?)]
       [SaveResetForm
        :can-save? changed?
        :can-reset? changed?
        :on-save #(save-permissions)
        :on-reset #(reset-permissions)
        :saving?
        (and changed?
             (or (loading/any-action-running? :only :project/change-permissions)
                 (loading/item-loading? [:project project-id])))])]))

(defn- ProjectMembersBox []
  [:div.ui.segment.project-members
   [ProjectMembersList]
   [ProjectPermissionsForm]])

;; TODO: do not render this unless project can be deleted (i.e. no labels);
;; currently allows clicking this and then fails server-side
(defn- DeleteProject
  "Delete a project"
  []
  (let [confirming? (r/cursor state [:confirming?])
        active-project-id (subscribe [:active-project-id])
        reviewed (-> @(subscribe [:project/article-counts])
                     :reviewed)
        members-count (count @(subscribe [:project/member-user-ids nil true]))
        delete-action (cond (= reviewed 0)
                            :delete

                            (and (< reviewed 20) (< members-count 4))
                            :disable

                            :else nil)
        enable-button? (not (nil? delete-action))]
    (when (admin?)
      [:div.ui.segment
       [:div.ui.relaxed.divided.list
        (when (and @confirming? delete-action)
          (let [[title message action-color]
                (case delete-action
                  :delete
                  ["Delete this project?"
                   "All articles/labels/notes will be lost."
                   "orange"]

                  :disable
                  ["Disable this project?"
                   "It will be inaccessible until re-enabled."
                   "yellow"]

                  nil)]
            [ConfirmationDialog
             {:on-cancel #(reset! confirming? false)
              :on-confirm
              (fn []
                (reset! confirming? false)
                (dispatch [:action [:project/delete @active-project-id]]))
              :title title
              :message message
              :action-color action-color}]))
        (when-not @confirming?
          [:button.ui.button
           {:class (if enable-button? "" "disabled")
            :on-click
            (when enable-button? #(reset! confirming? true))}
           (if (= delete-action :delete)
             "Delete Project..."
             "Disable Project...")])]])))

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
          [ProjectOptionsBox]]
         [:div.ui.column
          [ProjectMembersBox]
          [DeleteProject]]]]])))
