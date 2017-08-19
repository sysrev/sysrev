(ns sysrev.views.panels.project.settings
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [with-tooltip]]))

(def ^:private panel-name [:project :project :settings])

(defn- parse-input [skey input]
  (case skey
    :second-review-prob
    (let [n (js/parseInt input)]
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
 (fn [_ [values saved]]
   (let [changed-keys (filter #(not= (get values %)
                                     (get saved %))
                              (keys values))
         changes  (mapv (fn [skey]
                          {:setting skey
                           :value (get values skey)})
                        changed-keys)]
     (dispatch [:action [:project/change-settings changes]]))))

(defn- project-options-box []
  (let [admin? (or @(subscribe [:member/admin?])
                   @(subscribe [:user/admin?]))
        values @(subscribe [::values])
        saved @(subscribe [::saved])
        modified? @(subscribe [::modified?])
        valid? @(subscribe [::valid-input?])
        field-class #(if @(subscribe [::valid-input? %])
                       "" "error")]
    [:div.ui.grey.segment
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
                (dispatch [::edit-setting skey input]))
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
    [:div.ui.grey.segment
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

(defmethod panel-content [:project :project :settings] []
  (fn [child]
    (let [user-id @(subscribe [:self/user-id])
          admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))]
      [:div.ui.segment
       (when (not admin?)
         [:h3 [:div.ui.large.fluid.label
               {:style {:text-align "center"}}
               "Read-only"]])
       [:div.ui.two.column.stackable.grid.project-settings
        [:div.ui.row
         [:div.ui.column
          [project-options-box]]
         [:div.ui.column
          [project-permissions-box]]]]])))
