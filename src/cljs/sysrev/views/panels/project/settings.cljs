(ns sysrev.views.panels.project.settings
  (:require
   [reagent.core :as r]
   [re-frame.core :refer
    [subscribe dispatch dispatch-sync reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.action.core :refer [def-action]]
   [sysrev.state.nav :refer [active-project-id]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [with-tooltip]]
   [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
   [sysrev.shared.util :refer [parse-integer]]))

(def ^:private panel-name [:project :project :settings])

(def initial-state {:confirming? false})
(def state (r/atom initial-state))

(defn- parse-input [skey input]
  (case skey
    :second-review-prob
    (let [n (parse-integer input)]
      (when (and (int? n) (>= n 0) (<= n 100))
        (* n 0.01)))
    nil))

(defn- render-setting [skey]
  (if-let [input @(subscribe [::active-inputs skey])]
    input
    (when-let [value @(subscribe [::values skey])]
      (case skey
        :second-review-prob
        (when (float? value)
          (str (int (+ 0.5 (* value 100)))))
        nil))))

(reg-sub
 ::editing?
 :<- [:active-panel]
 :<- [:member/admin?]
 :<- [:user/admin?]
 (fn [[panel admin? site-admin?]]
   (and (= panel panel-name)
        (or admin? site-admin?))))

(reg-sub
 ::saved
 :<- [:project/settings]
 (fn [settings [_ skey]]
   (cond-> settings
     skey (get skey))))

(reg-sub
 ::active
 (fn [db [_ skey]]
   (cond-> (get-in db [:state :project-settings :active-values])
     skey (get skey))))

(reg-sub
 ::values
 :<- [::editing?]
 :<- [::saved]
 :<- [::active]
 (fn [[editing? saved active] [_ skey]]
   (cond-> saved
     editing? (merge active)
     skey (get skey))))

(reg-sub
 ::active-inputs
 (fn [db [_ skey]]
   (cond-> (get-in db [:state :project-settings :active-inputs])
     skey (get skey))))

(reg-event-db
 ::reset-fields
 (fn [db]
   (-> db
       (assoc-in [:state :project-settings :active-values] {})
       (assoc-in [:state :project-settings :active-inputs] {}))))

(reg-sub
 ::valid-input?
 :<- [::active-inputs]
 :<- [::values]
 (fn [[inputs values] [_ skey]]
   (letfn [(valid? [skey]
             (if-let [input (get inputs skey)]
               (= (parse-input skey input)
                  (get values skey))
               true))]
     (if skey
       (valid? skey)
       (every? valid? (keys inputs))))))

(reg-event-db
 ::edit-setting
 [trim-v]
 (fn [db [skey input]]
   (let [value (parse-input skey input)]
     (cond-> (assoc-in db [:state :project-settings :active-inputs skey] input)
       value (assoc-in [:state :project-settings :active-values skey] value)))))

(reg-sub
 ::modified?
 :<- [::saved]
 :<- [::values]
 (fn [[saved values]]
   (not= saved values)))

(reg-event-fx
 ::save-changes
 [trim-v]
 (fn [{:keys [db]} [values saved]]
   (let [changed-keys (filter #(not= (get values %)
                                     (get saved %))
                              (keys values))
         changes (mapv (fn [skey]
                         {:setting skey
                          :value (get values skey)})
                       changed-keys)
         project-id (active-project-id db)]
     (dispatch [:action [:project/change-settings project-id changes]]))))

(def-action :project/delete
  :uri (fn [] "/api/delete-project")
  :content (fn [project-id] {:project-id project-id})
  :process
  (fn [{:keys [db]} _ result]
    {:db (-> db
             (assoc-in [:state :active-project-id] nil))
     :dispatch-n (list [:navigate [:select-project]]
                       [:fetch [:identity]])}))

(defn- project-options-box []
  (let [admin? (or @(subscribe [:member/admin?])
                   @(subscribe [:user/admin?]))
        values @(subscribe [::values])
        saved @(subscribe [::saved])
        modified? @(subscribe [::modified?])
        valid? @(subscribe [::valid-input?])
        field-class #(if @(subscribe [::valid-input? %])
                       "" "error")]
    [:div.ui.segment
     [:h4.ui.dividing.header "Configuration options"]
     [:div.ui.form {:class (if valid? "" "warning")}
      (let [skey :second-review-prob]
        [:div.fields
         [:div.field {:class (field-class skey)}
          [with-tooltip
           [:label "Double-review priority "
            [:i.ui.large.grey.circle.question.mark.icon]]
           {:delay {:show 200
                    :hide 0}
            :hoverable false}]
          [:div.ui.popup.transition.hidden.tooltip
           [:p "Controls proportion of articles assigned for second review in Classify task."]
           [:p "0% will assign unreviewed articles whenever possible."]
           [:p "100% will assign for second review whenever possible."]]
          [:div.ui.right.labeled.input
           [:input
            {:type "text"
             :name (str skey)
             :value (render-setting skey)
             :on-change
             #(let [input (-> % .-target .-value)]
                (dispatch-sync [::edit-setting skey input]))
             :readOnly (if admin? false true)
             :autoComplete "off"}]
           [:div.ui.basic.label "%"]]]])]
     (when admin?
       [:div
        [:div.ui.divider]
        [:div
         [:button.ui.primary.button
          {:class (if (and valid? modified?) "" "disabled")
           :on-click #(dispatch [::save-changes values saved])}
          "Save changes"]
         [:button.ui.button
          {:class (if modified? "" "disabled")
           :on-click #(dispatch [::reset-fields])}
          "Reset"]]])]))

(defn- project-permissions-box []
  (let [;; TODO: implement "owner" permission
        owner? false
        all-perms (if owner? ["admin" "resolve"] ["resolve"])
        user-ids @(subscribe [:project/member-user-ids nil true])]
    [:div.ui.segment
     [:h4.ui.dividing.header "Users"]
     [:div.ui.relaxed.divided.list
      (doall
       (for [user-id user-ids]
         [:div.item {:key user-id}
          [:div.right.floated.content
           (let [permissions @(subscribe [:member/permissions user-id])]
             (doall
              (for [perm permissions]
                [:div.ui.tiny.label {:key perm} perm])))]
          [:div.content
           {:style {:padding-top "4px"
                    :padding-bottom "4px"}}
           [:i.user.icon]
           @(subscribe [:user/display user-id])]]))]
     ;; TODO: finish permissions editor interface
     #_
     (when false
       [:div
        [:div.ui.divider]
        [:form.ui.form
         [:div.fields
          [project-members-dropdown :add-permission-user
           #(reset-field :add-permission-perm)]
          [permissions-dropdown :add-permission-perm all-perms]
          [:div.field
           [:label nbsp]
           [:div.ui.fluid.icon.button [:i.plus.icon]]]]]
        [:form.ui.form
         [:div.fields
          [project-members-dropdown :remove-permission-user
           #(reset-field :remove-permission-perm)]
          [permissions-dropdown :remove-permission-perm all-perms]
          [:div.field
           [:label nbsp]
           [:div.ui.fluid.icon.button [:i.minus.icon]]]]]])]))

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

(defn DeleteProject
  "Delete a project"
  []
  (let [confirming? (r/cursor state [:confirming?])
        active-project-id (subscribe [:active-project-id])]
    [:div.ui.segment
     [:h4.ui.dividing.header "Delete Project"]
     [:div.ui.relaxed.divided.list
      (when @confirming?
        [ConfirmationAlert {:cancel-on-click #(reset! confirming? false)
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
    (let [user-id @(subscribe [:self/user-id])
          admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))]
      (reset! state initial-state)
      [:div.project-content
       [ReadOnlyMessage
        "Changing settings is restricted to project administrators."]
       [:div.ui.two.column.stackable.grid.project-settings
        [:div.ui.row
         [:div.ui.column
          [project-options-box]]
         [:div.ui.column
          [project-permissions-box]
          (when @(subscribe [:member/admin?])
            [DeleteProject])]]]])))
