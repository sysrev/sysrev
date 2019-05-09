(ns sysrev.views.panels.project.settings
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :as ui]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.panels.project.compensation
             :refer [ProjectCompensations CompensationSummary UsersCompensations]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in?]]))

(def ^:private panel [:project :project :settings])

(def initial-state {:confirming? false})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn- parse-input [skey input]
  (case skey
    input))

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

(defn misc-active [& [skey]]
  (cond-> (:misc-active @state)
    skey (get skey)))

(defn misc-saved [& [skey]]
  (let [project-name @(subscribe [:project/name])]
    {:project-name project-name}))

(defn misc-current [& [skey]]
  (let [active (misc-active)]
    (cond-> (misc-saved)
      (editing?) (merge active)
      skey (get skey))))

(defn misc-inputs [& [skey]]
  (cond-> (:misc-inputs @state)
    skey (get skey)))

(defn edit-misc [skey value]
  (let [inputs (r/cursor state [:misc-inputs])
        values (r/cursor state [:misc-active])]
    (swap! values assoc skey value)
    (swap! inputs assoc skey value)))

(defn misc-modified? []
  (not= (misc-saved) (misc-current)))

(defn misc-valid? [& [skey]]
  (let [current (misc-current)]
    (letfn [(valid? [skey]
              (let [value (get current skey)]
                (boolean
                 (case skey
                   :project-name
                   (and (string? value)
                        (> (count (str/trim value)) 0)
                        (<= (count (str/trim value)) 200))
                   false))))]
      (if skey
        (valid? skey)
        (every? valid? (keys current))))))

(defn- misc-field-class [skey]
  (if (misc-valid? skey) "" "error"))

(defn reset-misc []
  (let [values (r/cursor state [:misc-active])
        inputs (r/cursor state [:misc-inputs])]
    (reset! values {})
    (reset! inputs {})))

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

(def-action :project/change-name
  :uri (fn [project-id project-name] "/api/change-project-name")
  :content (fn [project-id project-name]
             {:project-id project-id :project-name project-name})
  :process (fn [_ [project-id _] {:keys [success]}]
             (when success
               {:dispatch-n
                (list [:reload [:identity]]
                      [:reload [:public-projects]]
                      [:reload [:project project-id]])})))

(defn save-changes [project-id]
  (let [values (current-values)
        saved (saved-values)
        changed-keys (filter #(not= (get values %) (get saved %))
                             (keys values))
        changes (mapv (fn [skey]
                        {:setting skey
                         :value (get values skey)})
                      changed-keys)]
    (dispatch [:action [:project/change-settings project-id changes]])))

(defn save-misc [project-id]
  (let [values (misc-current)
        saved (misc-saved)
        changed-keys (filter #(not= (get values %) (get saved %))
                             (keys values))]
    (when (in? changed-keys :project-name)
      (let [project-name (some-> values :project-name str/trim)]
        (edit-misc :project-name project-name)
        (when (not= project-name (:project-name saved))
          (dispatch [:action [:project/change-name project-id project-name]]))))))

(defn- render-setting-value [skey value]
  (case skey
    :second-review-prob
    (cond (float? value)  (int (+ 0.5 (* value 100)))
          :else           value)

    :public-access (boolean value)

    :unlimited-reviews (boolean value)

    nil))

(defn- render-setting [skey]
  (render-setting-value skey (current-values skey)))

(def-action :project/delete
  :uri (fn [] "/api/delete-project")
  :content (fn [project-id] {:project-id project-id})
  :process (fn [{:keys [db]} _ result]
             {:db (assoc-in db [:state :active-project-id] nil)
              :dispatch [:reload [:identity]]
              :nav-scroll-top "/"}))

(defn- input-field-class [skey]
  (if (valid-input? skey) "" "error"))

(def review-priority-buttons
  [{:key :single
    :label "Single"
    :value 0
    :tooltip "Prioritize single-user review of articles"}
   {:key :balanced
    :label "Balanced"
    :value 0.5
    :tooltip "Assign mix of unreviewed and partially-reviewed articles"}
   {:key :full
    :label "Full"
    :value 1.0
    :tooltip "Prioritize fully reviewed articles"}])

(defn- SettingsButton [{:keys [setting key label value tooltip disabled?]}]
  (let [active? (= (render-setting setting)
                   (render-setting-value setting value))
        admin? (admin?)]
    (ui/FixedTooltipElementManual
     [:button.ui.button
      {:id (str (name setting) "_" (name key))
       :class (cond-> " "
                active? (str "active")
                disabled? (str " disabled"))
       :on-click (if admin? #(edit-setting setting value) nil)}
      label]
     [:p tooltip]
     "20em"
     :props {:style {:text-align "center"}})))

(defn- SettingsField [{:keys [setting label entries disabled?]} entries]
  (let [elements (->> entries
                      (map #(SettingsButton (merge % {:setting setting
                                                      :disabled? disabled?}))))]
    [:div.field {:id (str "project-setting_" (name setting))
                 :class (input-field-class setting)}
     [:label label]
     [:div.ui.fluid.buttons.selection
      (doall
       (for [[button _] elements]
         ^{:key [:button (hash button)]} [button]))]
     (doall
      (for [[_ tooltip] elements]
        ^{:key [:tooltip (hash tooltip)]} [tooltip]))]))

(defn- DoubleReviewPriorityField []
  [SettingsField
   {:setting :second-review-prob
    :label "Article Review Priority"
    :entries review-priority-buttons}])

(def public-access-buttons
  [{:key :public
    :label [:span "Public"]
    :value true
    :tooltip "Allow anyone to view project"}
   {:key :private
    :label [:span "Private"]
    :value false
    :tooltip "Allow access only for project members"}])

(defn- PublicAccessField [project-id]
  (let [owner-key (-> (get-in @app-db [:data :project project-id :owner]) keys first)
        project-plan (get-in @app-db [:data :project project-id :plan])]
    [:div [SettingsField
           {:setting :public-access
            :label "Project Visibility"
            :entries public-access-buttons
            :disabled? (= project-plan "Basic")}]
     (when (= project-plan
              "Basic")
       [:p [:a {:href (if (= owner-key :user-id)
                        (str "/user/" @(subscribe [:self/user-id]) "/billing")
                        "/org/billing")}
            "Upgrade"] " your plan to make this project private"])]))

(def unlimited-reviews-buttons
  [{:key :false
    :label [:span "No"]
    :value false
    :tooltip "Limit of two users assigned per article"}
   {:key :true
    :label [:span "Yes"]
    :value true
    :tooltip "Users may be assigned any article they have not yet reviewed"}])

(defn- UnlimitedReviewsField []
  [SettingsField
   {:setting :unlimited-reviews
    :label "Allow Unlimited Reviews"
    :entries unlimited-reviews-buttons}])

(defn ProjectNameField []
  (let [skey :project-name
        admin? (admin?)
        current (misc-current skey)
        saved (misc-saved skey)
        modified? (not= current saved)]
    [:div.field.project-name
     {:class (misc-field-class skey)}
     [:label "Project Name"]
     [:textarea
      {:readOnly (not admin?)
       :rows 3
       :value current
       :on-change (when admin?
                    (util/wrap-prevent-default
                     #(edit-misc skey (-> % .-target .-value))))}]]))

(defn- DeleteProjectForm []
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
    (when (and (admin?) (or (not @confirming?) delete-action))
      [:div.ui.segment
       (if @confirming?
         (when delete-action
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
             [ui/ConfirmationDialog
              {:on-cancel #(reset! confirming? false)
               :on-confirm
               (fn []
                 (reset! confirming? false)
                 (dispatch [:action [:project/delete @active-project-id]]))
               :title title
               :message message
               :action-color action-color}]))
         [:div.ui.form.delete-project
          [:div.field
           [:button.ui.fluid.button
            {:class (if enable-button? "" "disabled")
             :on-click
             (when enable-button? #(reset! confirming? true))}
            (if (= delete-action :delete)
              "Delete Project..."
              "Disable Project...")]]])])))

(defn- ProjectMiscBox []
  (let [saving? (r/atom false)]
    (fn []
      (let [admin? (admin?)
            modified? (misc-modified?)
            project-id @(subscribe [:active-project-id])]
        (when (and @saving?
                   (not modified?)
                   (not (loading/any-action-running?)))
          (reset! saving? false))
        [:div.ui.segment.project-misc
         [:h4.ui.dividing.header "Project"]
         [:div.ui.form
          [ProjectNameField]]
         (when admin?
           [:div
            [:div.ui.divider]
            [ui/SaveCancelForm
             :can-save? (and (misc-valid?) modified?)
             :can-reset? modified?
             :on-save #(do (reset! saving? true)
                           (save-misc project-id))
             :on-reset #(do (reset! saving? false)
                            (reset-misc))
             :saving? @saving?]])]))))

(defn- ProjectOptionsBox []
  (let [saving? (r/atom false)]
    (fn []
      (let [admin? (admin?)
            values (current-values)
            saved (saved-values)
            modified? (modified?)
            valid? (valid-input?)
            field-class #(if (valid-input? %) "" "error")
            project-id @(subscribe [:active-project-id])]
        (when (and @saving?
                   (not modified?)
                   (not (loading/any-action-running?
                         :only :project/change-settings)))
          (reset! saving? false))
        [:div.ui.segment.project-options
         [:h4.ui.dividing.header "Options"]
         [:div.ui.form {:class (if valid? "" "warning")}
          [:div.two.fields
           [PublicAccessField project-id]
           [DoubleReviewPriorityField]]
          [:div.two.fields
           [UnlimitedReviewsField]]]
         (when admin?
           [:div
            [:div.ui.divider]
            [ui/SaveCancelForm
             :can-save? (and valid? modified?)
             :can-reset? modified?
             :on-save #(do (reset! saving? true)
                           (save-changes project-id))
             :on-reset #(do (reset! saving? false)
                            (reset-fields))
             :saving? @saving?
             :id "save-options"]])]))))

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
    [ui/selection-dropdown
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
        (let [user-id (sutil/parse-integer value)]
          (swap! members-state
                 assoc :selected-user user-id)))}]))

(def permission-values ["admin"])

(defn- MemberPermissionDropdown []
  (let [{:keys [selected-user selected-permission]} @members-state]
    [ui/selection-dropdown
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
       [:div.seven.wide.field.user-select
        [UserSelectDropdown]]
       [:div.six.wide.field.permission-select
        [MemberPermissionDropdown]]
       [:div.three.wide.field
        [:div.ui.fluid.buttons
         [AddPermissionButton]
         [RemovePermissionButton]]]]]
     [:div.ui.divider]
     (let [project-id @(subscribe [:active-project-id])
           changed? (permissions-changed?)]
       [ui/SaveCancelForm
        :can-save? changed?
        :can-reset? changed?
        :on-save #(save-permissions)
        :on-reset #(do (reset-permissions)
                       (reset-permissions-fields))
        :saving?
        (and changed?
             (or (loading/any-action-running? :only :project/change-permissions)
                 (loading/item-loading? [:project project-id])))])]))

(defn- ProjectMembersBox []
  [:div.ui.segment.project-members
   [ProjectMembersList]
   [ProjectPermissionsForm]])

(def-action :project/update-predictions
  :uri (fn [project-id] "/api/update-project-predictions")
  :content (fn [project-id] {:project-id project-id})
  :process
  (fn [_ _ _]
    {:dispatch [:set-panel-field [:update-predictions-clicked] true panel]
     :dispatch-later
     [{:ms 2000
       :dispatch [:set-panel-field [:update-predictions-clicked] nil panel]}]}))

(defn- DeveloperActions []
  (when @(subscribe [:user/admin?])
    (let [project-id @(subscribe [:active-project-id])]
      [:div.ui.segments>div.ui.segment
       [:h4.ui.dividing.header "Developer Actions"]
       (let [clicked? @(subscribe [:panel-field [:update-predictions-clicked]])]
         [:div.ui.fluid.right.labeled.icon.button
          {:class (if clicked? "green" "blue")
           :on-click #(dispatch [:action [:project/update-predictions project-id]])}
          (if clicked? "Updating" "Update Predictions")
          (if clicked? [:i.check.circle.icon] [:i.repeat.icon])])])))

(defmethod panel-content [:project :project :settings] []
  (fn [child]
    (ensure-state)
    (let [user-id @(subscribe [:self/user-id])
          admin? (admin?)]
      [:div.project-content
       [ReadOnlyMessage
        "Changing settings is restricted to project administrators."
        (r/cursor state [:read-only-message-closed?])]
       [:div.ui.two.column.stackable.grid.project-settings
        [:div.ui.row
         [:div.ui.column
          [ProjectMiscBox]
          [ProjectOptionsBox]
          [DeleteProjectForm]]
         [:div.ui.column
          [ProjectMembersBox]
          [DeveloperActions]]]]])))
