(ns sysrev.views.panels.user-settings
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [with-tooltip selection-dropdown]]
   [sysrev.nav :refer [nav-scroll-top]]
   [sysrev.util :refer [full-size?]]))

(def ^:private panel-name [:user-settings])

(defn- parse-input [skey input]
  (case skey
    :ui-theme input
    nil))

(defn- render-setting [skey]
  (if-let [input @(subscribe [::active-inputs skey])]
    input
    (let [value @(subscribe [::values skey])]
      (case skey
        :ui-theme (if (nil? value) "Default" value)
        nil))))

(reg-sub
 ::editing?
 :<- [:active-panel]
 (fn [panel]
   (= panel panel-name)))

(reg-sub
 ::saved
 :<- [:self/settings]
 (fn [settings [_ skey]]
   (cond-> (into {} settings)
     skey (get skey))))

(reg-sub
 ::active
 (fn [db [_ skey]]
   (cond-> (get-in db [:state :user-settings :active-values])
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
   (cond-> (get-in db [:state :user-settings :active-inputs])
     skey (get skey))))

(reg-event-db
 ::reset-fields
 (fn [db]
   (-> db
       (assoc-in [:state :user-settings :active-values] {})
       (assoc-in [:state :user-settings :active-inputs] {}))))

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
     (cond-> (assoc-in db [:state :user-settings :active-inputs skey] input)
       value (assoc-in [:state :user-settings :active-values skey] value)))))

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
     (dispatch [:action [:user/change-settings changes]]))))

(defn- theme-selector []
  (let [active-theme (render-setting :ui-theme)]
    [selection-dropdown
     [:div.text active-theme]
     (->> ["Default" "Dark"]
          (mapv
           (fn [theme-name]
             [:div.item
              (into {:key theme-name
                     :on-click #(dispatch [::edit-setting :ui-theme theme-name])}
                    (when (= theme-name active-theme)
                      {:class "active selected"}))
              theme-name])))]))

(defn- user-options-box []
  (let [values @(subscribe [::values])
        saved @(subscribe [::saved])
        modified? @(subscribe [::modified?])
        valid? @(subscribe [::valid-input?])
        field-class #(if @(subscribe [::valid-input? %])
                       "" "error")]
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
         :on-click #(dispatch [::save-changes values saved])}
        "Save changes"]
       [:button.ui.button
        {:class (if modified? "" "disabled")
         :on-click #(dispatch [::reset-fields])}
        "Reset"]]]]))

(defn- user-dev-tools-box []
  (let [user-id @(subscribe [:self/user-id])]
    (when @(subscribe [:user/admin?])
      [:div.ui.segment
       [:h4.ui.dividing.header "Dev Tools"]
       [:div
        #_ [:button.ui.yellow.button
            {:on-click
             #(do (dispatch [:action [:user/delete-member-labels user-id]])
                  (nav-scroll-top "/"))}
            "Delete Member Labels"]
        [:button.ui.orange.button
         {:on-click
          #(dispatch [:action [:user/delete-account user-id]])}
         "Delete Account"]]])))

(defmethod panel-content [:user-settings] []
  (fn [child]
    [:div
     [:div.ui.segment
      [:h4 "User Settings"]]
     [:div.ui.two.column.stackable.grid
      [:div.column [user-options-box]]
      [:div.column [user-dev-tools-box]]]]))
