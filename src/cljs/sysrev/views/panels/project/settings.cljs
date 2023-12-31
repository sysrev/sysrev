(ns sysrev.views.panels.project.settings
  (:require ["@material-ui/icons/Block$default" :as BlockIcon]
            [clojure.string :as str]
            [re-frame.core :refer [dispatch reg-event-db reg-sub subscribe
                                   trim-v]]
            [reagent.core :as r]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :as data]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.nav :as nav]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.util :as util :refer [css in? parse-integer when-test]]
            [sysrev.views.components.core :as ui :refer [CursorMessage]]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.semantic :as S :refer [Divider Modal ModalContent
                                                 ModalDescription ModalHeader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :settings]
                   :state state :get [panel-get] :set [panel-set])

(reg-event-db :project-settings/reset-state! #(panel-set % nil {}))

(reg-sub      ::error-message #(panel-get % :error-message))
(reg-event-db ::error-message [trim-v]
              (fn [db [msg]] (panel-set db :error-message msg)))

(defn- parse-input [skey input]
  (case skey
    input))

(defn admin? []
  @(subscribe [:member/admin? true]))

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

(defn misc-saved [& [_skey]]
  (let [project-name @(subscribe [:project/name])]
    {:project-name project-name}))

(defn misc-current [& [skey]]
  (let [active (misc-active)]
    (cond-> (misc-saved)
      (editing?) (merge active)
      skey (get skey))))

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
  :uri (fn [_ _] "/api/change-project-settings")
  :content (fn [project-id changes]
             {:project-id project-id :changes changes})
  :process (fn [{:keys [db]} [project-id _] {:keys [settings]}]
             ;; if project is owned by an org, also reload that orgs projects
             ;; this is hacky, need a keyword to [:action [...] :on-success #(do something)]
             ;; because this can't just be handled by sysrev.views.panels.user.projects/set-public!
             (let [self-id (current-user-id db)
                   {:keys [group-id]} @(subscribe [:project/owner project-id])]
               (when group-id
                 (dispatch [:data/load [:org/projects group-id]]))
               {:db (assoc-in db [:data :project project-id :settings] settings)
                :dispatch [:reload [:user/projects self-id]]})))

(def-action :project/change-name
  :uri (fn [_ _] "/api/change-project-name")
  :content (fn [project-id project-name]
             {:project-id project-id :project-name project-name})
  :process (fn [_ [project-id _] {:keys [success]}]
             (when success
               {:dispatch-n
                (list [:reload [:identity]]
                      [:reload [:public-projects]]
                      [:reload [:project project-id]])}))
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db :error-message (:message error))}))

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
    :second-review-prob (cond (float? value)  (int (+ 0.5 (* value 100)))
                              :else           value)
    :public-access      (boolean value)
    :unlimited-reviews  (boolean value)
    :blind-reviewers    (boolean value)
    :auto-save-labels   (boolean value)
    :freeze-model       (boolean value)
    :gpt-access         (boolean value)
    nil))

(defn- render-setting [skey]
  (render-setting-value skey (current-values skey)))

(def-action :project/disable
  :uri (fn [] "/api/disable-project")
  :content (fn [project-id] {:project-id project-id})
  :process (fn [{:keys [db]} _ _]
             {:db (assoc-in db [:state :active-project-id] nil)
              :dispatch [:reload [:identity]]
              :nav ["/"]}))

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
                   (render-setting-value setting value))]
    [ui/Tooltip
     {:trigger [:button.ui.button
                {:id (str (name setting) "_" (name key))
                 :class (css [active? "active"]
                             [disabled? "disabled"])
                 :on-click (if (admin?) #(edit-setting setting value) nil)}
                label]
      :tooltip [:p tooltip]
      :style {:min-width "20em" :text-align "center"}}]))

(defn- SettingsField [{:keys [setting label entries disabled?]} & [content]]
  [:div.field {:id (str "project-setting_" (name setting))
               :class (input-field-class setting)}
   [:label label]
   [:div.ui.fluid.buttons.selection
    (doall (for [[i entry] (map-indexed vector entries)] ^{:key i}
             [SettingsButton (merge entry {:setting setting
                                           :disabled? disabled?})]))]
   (when content [content])])

(defn- DoubleReviewPriorityField []
  [SettingsField {:setting :second-review-prob
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
  (let [self-id @(subscribe [:self/user-id])
        project-owner @(subscribe [:project/owner project-id])
        owner-type (-> project-owner keys first)
        owner-id (-> project-owner vals first)
        project-plan @(subscribe [:project/plan project-id])
        project-url @(subscribe [:project/uri project-id])]
    [SettingsField
     {:setting :public-access
      :label "Project Visibility"
      :entries public-access-buttons
      :disabled? (and (= project-plan "Basic")
                      (not @(subscribe [:user/dev?]))
                      @(subscribe [:project/public-access? project-id]))}
     (when (and (= project-plan "Basic")
                @(subscribe [:project/controlled-by? project-id self-id]))
       (fn []
         [:p [:a {:href (nav/make-url (if (= owner-type :user-id)
                                        "/user/plans"
                                        (str "/org/" owner-id "/plans"))
                                      {:on_subscribe_uri
                                       (str project-url "/settings")})}
              "Upgrade"] (str " " (if (= owner-type :user-id)
                                    "your"
                                    "the organization's")
                              " plan to make this project private.")]))]))

(def unlimited-reviews-buttons
  [{:key :false
    :label [:span "No"]
    :value false
    :tooltip "Limit of two users assigned per article"}
   {:key :true
    :label [:span "Yes"]
    :value true
    :tooltip "Users may be assigned any article they have not yet reviewed"}])

(def auto-save-labels-buttons
  [{:key :false
    :label [:span "No"]
    :value false
    :tooltip "You must manually press save when reviewing"}
   {:key :true
    :label [:span "Yes"]
    :value true
    :tooltip "Once all labels are set they will automatically save"}])

(defn- UnlimitedReviewsField []
  [SettingsField {:setting :unlimited-reviews
                  :label "Allow Unlimited Reviews"
                  :entries unlimited-reviews-buttons}])

(defn- AutoSaveLabelsField []
  [SettingsField {:setting :auto-save-labels
                  :label "Automatically Save Review Labels?"
                  :entries auto-save-labels-buttons}])

(defn- BlindReviewersField [project-id]
  (let [self-id @(subscribe [:self/user-id])
        project-owner @(subscribe [:project/owner project-id])
        owner-type (-> project-owner keys first)
        owner-id (-> project-owner vals first)
        project-plan @(subscribe [:project/plan project-id])
        project-url @(subscribe [:project/uri project-id])]
    [SettingsField {:setting :blind-reviewers
                    :label "Label Blinding"
                    :disabled? (and (= project-plan "Basic")
                                    (not @(subscribe [:user/dev?]))
                                    @(subscribe [:project/public-access? project-id]))
                    :entries   [{:key :false
                                 :label [:span "No"]
                                 :value false
                                 :tooltip "User answers visible in article list and in individual articles"}
                                {:key :true
                                 :label [:span "Yes"]
                                 :value true
                                 :tooltip "User answers hidden everywhere except to administrators"}]}
     (when (and (= project-plan "Basic")
                @(subscribe [:project/controlled-by? project-id self-id]))
       (fn []
         [:p [:a {:href (nav/make-url (if (= owner-type :user-id)
                                        "/user/plans"
                                        (str "/org/" owner-id "/plans"))
                                      {:on_subscribe_uri
                                       (str project-url "/settings")})}
              "Upgrade"] (str " " (if (= owner-type :user-id)
                                    "your"
                                    "the organization's")
                              " plan to enable label blinding. Label blinding hides answers from non-admin reviewers.")]))]))

(defn- FreezeModelField []
  [SettingsField
   {:setting :freeze-model
    :label "Freeze Model"
    :disabled? false
    :entries
    [{:key :false
      :label [:span "No"]
      :value false
      :tooltip "Model not frozen"}
     {:key :true
      :label [:span "Yes"]
      :value true
      :tooltip "Model frozen"}]}])

(defn- GPTAccessField []
  [SettingsField
   {:setting :gpt-access
    :label "GPT Access"
    :disabled? false
    :entries
    [{:key :false
      :label [:span "No"]
      :value false
      :tooltip "GPT access not enabled"}
     {:key :true
      :label [:span "Yes"]
      :value true
      :tooltip "GPT access enabled"}]}])

(defn ProjectNameField []
  (let [skey :project-name
        admin? (admin?)
        current (misc-current skey)
        error @(subscribe [::error-message])]
    [:div.field.project-name {:class (misc-field-class skey)}
     [:label "Project Name"]
     [:textarea {:readOnly (not admin?)
                 :rows 3
                 :value current
                 :on-change (when admin?
                              (util/wrap-prevent-default
                               #(edit-misc skey (-> % .-target .-value))))}]
     (when error
       [:<>
        [Divider]
        [:div.ui.error.message {:style {:display "block"}}
         error]])]))

(defn- DisableProjectForm []
  (let [confirming-disable (r/cursor state [:confirming-disable])
        project-id @(subscribe [:active-project-id])]
    [:div.disable-project-toplevel
     (if @confirming-disable
       [ui/ConfirmationDialog
        {:on-cancel #(reset! confirming-disable false)
         :on-confirm #(do (reset! confirming-disable false)
                          (dispatch [:action [:project/disable project-id]]))
         :title "Disable this project?"
         :message "It will be inaccessible until re-enabled."
         :action-color "yellow"}]
       [:button.ui.fluid.button
        {:on-click #(reset! confirming-disable true)}
        "Disable Project..."])]))

(defn- ProjectExtraActions []
  (when (admin?)
    [:div.ui.secondary.segment.action-segment
     [DisableProjectForm]]))

(defn- ProjectMiscBox []
  (let [saving? (r/atom false)]
    (fn []
      (let [modified? (misc-modified?)
            project-id @(subscribe [:active-project-id])]
        (when (and @saving?
                   (not modified?)
                   (not (action/running?)))
          (reset! saving? false))
        [:div.ui.segment.project-misc
         [:h4.ui.dividing.header "Project"]
         [:div.ui.form
          [ProjectNameField]]
         (when (admin?)
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
      (let [modified? (modified?)
            valid? (valid-input?)
            project-id @(subscribe [:active-project-id])]
        (when (and @saving?
                   (not modified?)
                   (not (action/running? :project/change-settings)))
          (reset! saving? false))
        [:div.ui.segment.project-options
         [:h4.ui.dividing.header "Options"]
         [:div.ui.form {:class (if valid? "" "warning")}
          [PublicAccessField project-id]
          [DoubleReviewPriorityField]
          [UnlimitedReviewsField]
          [AutoSaveLabelsField]
          [BlindReviewersField project-id]
          [FreezeModelField]
          (when (re-matches #".*@insilica.co" @(subscribe [:self/email]))
            [GPTAccessField])]
         (when (admin?)
           [:div
            [:div.ui.divider]
            [ui/SaveCancelForm
             :id          "save-options"
             :can-save?   (and valid? modified?)
             :can-reset?  modified?
             :on-save     #(do (reset! saving? true)
                               (save-changes project-id))
             :on-reset    #(do (reset! saving? false)
                               (reset-fields))
             :saving?     @saving?]])]))))

(defonce members-state (r/cursor state [:members]))

(def-action :project/change-permissions
  :uri (fn [_ _] "/api/change-project-permissions")
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
  (swap! members-state dissoc :selected-permission :selected-user))

(defn save-permissions []
  (let [project-id @(subscribe [:active-project-id])
        changes (all-active-permissions)]
    (when (permissions-changed?)
      (dispatch [:action [:project/change-permissions
                          project-id changes]]))))

(def-action :project/remove-user
  :uri (fn [_ _] "/api/remove-user-from-project")
  :content (fn [project-id user-id _ _]
             {:project-id project-id :user-id user-id})
  :process (fn [{:keys [db]} [project-id _ modal-open _] {:keys [success]}]
             (reset! modal-open {:open false :user nil})
             {:dispatch [:reload [:project project-id]]})
  :on-error (fn [{:keys [db error]} [_ _ _ delete-failure]]
              (reset! delete-failure "Delete Failed. Please Try Again.")))

(defn DeleteMemberModal [modal-open]
  (let [delete-failure (r/atom nil)]
    (fn []
      (let [project-id @(subscribe [:active-project-id])]
        [:div
         [Modal {:trigger
                 (r/as-element
                  [:div.ui {:id :change-avatar
                            :data-tooltip "Report and Issue"
                            :data-position "bottom center"}])
                 :open (:open @modal-open)
                 :on-close #(reset! modal-open {:open false :user nil})
                 :size "tiny"}
          [ModalHeader "Delete User?"]
          [ModalContent
           [ModalDescription
            [:p (str "Confirm deletion of user: " @(subscribe [:user/username (:user @modal-open)]))]
            [:p "WARNING! THIS ACTION IS PERMANENT!"]
            [:button.ui.small.positive.button
             {:on-click #(run-action :project/remove-user project-id (:user @modal-open) modal-open delete-failure)}
             "Confirm"]
            [:button.ui.small.negitive.button {:on-click #(reset! modal-open {:open false :user nil})} "Cancel"]
            [CursorMessage delete-failure {:negative true}]]]]]))))

(defn- ProjectMembersList []
  (let [delete-modal-details (r/atom {:open false :user nil})]
    (fn []
      (let [user-ids (all-project-user-ids)
            user-permissions  (current-member-permissions @(subscribe [:self/user-id]))]
        [:div
         [DeleteMemberModal delete-modal-details]
         [:h4.ui.dividing.header "Members"]
         [:div.ui.relaxed.divided.list
          (doall
           (for [user-id user-ids]
             [:div.item {:key user-id}
              [:div.content.flex-between
               {:style {:padding-top "4px"
                        :padding-bottom "4px"}}
               [:div
                [:i.user.icon]
                @(subscribe [:user/username user-id])]
               [:div.content
                (let [saved (saved-member-permissions user-id)
                      current (current-member-permissions user-id)
                      permissions (distinct (concat saved current))]
                  [:div.flex-between
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
                       perm]))
                   [:div
                     (when (some #(or (= % "owner") (= % "admin")) user-permissions)
                       [:> BlockIcon {:class "project-member-delete" :on-click #(reset! delete-modal-details {:open true :user user-id})}])]])]]]))]]))))

(defn- UserSelectDropdown []
  [S/Dropdown {:selection true, :search true, :fluid true, :icon "dropdown"
               :placeholder "User"
               :options (for [user-id (all-project-user-ids)]
                          (let [user-name @(subscribe [:user/username user-id])]
                            {:key (or user-id "none")
                             :value (if user-id (str user-id) "none")
                             :text user-name
                             :content (r/as-element [:span [:i.user.icon] user-name])}))
               :on-change (fn [_event ^js x]
                            (swap! members-state assoc :selected-user
                                   (parse-integer (.-value x))))
               :value (some-> (:selected-user @members-state) str not-empty)}])

(def permission-values ["admin"])

(defn- MemberPermissionDropdown []
  [S/Dropdown {:selection true, :search true, :fluid true, :icon "dropdown"
               :placeholder "Permission"
               :options (for [perm permission-values]
                          {:key (or perm "none")
                           :value (or perm "none")
                           :text (or perm "none")
                           :content (r/as-element [:span [:i.key.icon] perm])})
               :on-change (fn [_event ^js x]
                            (swap! members-state assoc :selected-permission
                                   (when-test #(not (contains? #{"" "none"} %))
                                     (.-value x))))
               :value (:selected-permission @members-state)}])

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
        :on-save save-permissions
        :on-reset #(do (reset-permissions)
                       (reset-permissions-fields))
        :saving?
        (and changed?
             (or (action/running? :project/change-permissions)
                 (data/loading? [:project project-id])))])]))

(defn- ProjectMembersBox []
  [:div.ui.segment.project-members
   [ProjectMembersList]
   [ProjectPermissionsForm]])

(def-action :project/update-predictions
  :uri (fn [_] "/api/update-project-predictions")
  :content (fn [project-id] {:project-id project-id})
  :process (fn [_ _ _]
             {:dispatch [:set-panel-field [:update-predictions-clicked] true panel]
              :dispatch-later
              [{:ms 2000
                :dispatch [:set-panel-field [:update-predictions-clicked] nil panel]}]}))

(defn- DeveloperActions []
  (when @(subscribe [:user/dev?])
    (let [project-id @(subscribe [:active-project-id])]
      [:div.ui.segments>div.ui.secondary.segment.action-segment
       [:h4.ui.dividing.header "Developer Actions"]
       (let [clicked? @(subscribe [:panel-field [:update-predictions-clicked]])]
         [:div.ui.fluid.right.labeled.icon.button
          {:class (if clicked? "green" "blue")
           :on-click #(dispatch [:action [:project/update-predictions project-id]])}
          (if clicked? "Updating" "Update Predictions")
          (if clicked? [:i.check.circle.icon] [:i.repeat.icon])])])))

(defn- Panel []
  [:div.project-content
   [ReadOnlyMessage
    "Changing settings is restricted to project administrators."
    (r/cursor state [:read-only-message-closed?])]
   [:div.ui.two.column.stackable.grid.project-settings
    [:div.column
     [ProjectMiscBox]
     [ProjectOptionsBox]]
    [:div.column
     [ProjectMembersBox]
     [ProjectExtraActions]
     [DeveloperActions]]]])

(def-panel :project? true :panel panel
  :uri "/settings" :params [project-id] :name project-settings
  :on-route (do (data/reload :project project-id)
                (dispatch [:set-active-panel panel]))
  :content [Panel])
