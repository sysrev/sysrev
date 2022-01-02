(ns sysrev.views.panels.user.settings
  (:require [ajax.core :refer [PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.views.semantic :as S :refer
             [Segment Header Grid Column Radio Message]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.views.components.core :as ui]
            [sysrev.util :as util :refer [parse-integer format]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:user :settings]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

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

(defn- ThemeSelector []
  (let [active-theme (render-setting :ui-theme)]
    [S/Dropdown {:selection true :fluid true
                 :options (->> ["Default" "Dark"]
                               (mapv
                                 (fn [theme-name]
                                   {:key theme-name
                                    :value theme-name
                                    :text theme-name})))
                 :on-change (fn [_ selected-option]
                              (edit-setting :ui-theme (.-value selected-option)))
                 :value active-theme
                 :icon "dropdown"}]))

(defn- UserOptions []
  (let [modified? (modified?)
        valid? (valid-input?)
        field-class #(if (valid-input? %) "" "error")]
    [:div.ui.segment.user-options
     [:h4.ui.dividing.header "Options"]
     [:div.ui.unstackable.form {:class (if valid? "" "warning")}
      (let [skey :ui-theme]
        [:div.fields
         [:div.eight.wide.field {:class (field-class skey)}
          [:label "Web Theme"]
          [ThemeSelector]]])]
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

(defn- UserDevTools []
  (let [user-id @(subscribe [:self/user-id])]
    (when @(subscribe [:user/dev?])
      [:div.ui.segment
       [:h4.ui.dividing.header "Dev Tools"]
       [:div
        ;; TODO: add method for deleting dev user labels
        #_ [:button.ui.yellow.button
            {:on-click
             #(do (dispatch [:action [:user/delete-member-labels user-id]])
                  (nav/nav "/"))}
            "Delete Member Labels"]
        [:button.ui.orange.button
         {:on-click #(dispatch [:action [:user/delete-account user-id]])}
         "Delete Account"]]])))

(def-data :user/public-reviewer
  :uri      (fn [user-id] (format "/api/user/%d/groups/public-reviewer/active" user-id))
  :loaded?  (fn [db user-id] (-> (get-in db [:data :user-public-reviewer])
                                 (contains? user-id)))
  :process  (fn [{:keys [db]} [user-id] {:keys [enabled]}]
              {:db (assoc-in db [:data :user-public-reviewer user-id] (boolean enabled))}))

(reg-sub :user/public-reviewer
         (fn [db [_ user-id]]
           (get-in db [:data :user-public-reviewer user-id])))

(def-action :user/set-public-reviewer
  :method   :put
  :uri      (fn [user-id _] (format "/api/user/%d/groups/public-reviewer/active" user-id))
  :content  (fn [_ enabled] {:enabled enabled})
  :process  (fn [_ [user-id _] _] {:dispatch [:data/load [:user/public-reviewer user-id]]}))

(defn- PublicReviewerOptIn []
  (when-let [user-id @(subscribe [:self/user-id])]
    (with-loader [[:user/public-reviewer user-id]] {}
      (let [enabled @(subscribe [:user/public-reviewer user-id])
            verified @(subscribe [:self/verified])
            loading? (or (data/loading? :user/public-reviewer)
                         (action/running? :user/set-public-reviewer))]
        [Segment
         [Header {:as "h4" :dividing true} "Public Reviewer Opt In"]
         (when-not verified
           [Message {:warning true}
            [:a {:href (str "/user/" user-id "/email")}
             "Your email address is not yet verified."]])
         [Radio {:toggle true
                 :id "opt-in-public-reviewer"
                 :label "Publicly Listed as a Paid Reviewer"
                 :checked enabled
                 :disabled (or (not verified) loading?)
                 :on-click #(dispatch [:action [:user/set-public-reviewer
                                                user-id (not enabled)]])}]]))))

(defn EnableDevAccount []
  (let [enabled? (subscribe [:user/dev-account-enabled?])
        loading? (r/atom true)
        plan (subscribe [:user/current-plan])
        user-id @(subscribe [:self/user-id])
        error-message (r/atom "")
        toggle-dev-account! (fn [enabled?]
                              (reset! loading? true)
                              (PUT (str "/api/user/" user-id "/developer/enable")
                                   {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                    :params {:enabled? (not @enabled?)}
                                    :handler (fn [{:keys [result]}]
                                               (reset! loading? false)
                                               (dispatch [:fetch [:identity]]))
                                    :error-handler (fn [error-response]
                                                     (reset! loading? false)
                                                     (dispatch [:fetch [:identity]])
                                                     (reset! error-message (str "There was an error: " (get-in error-response [:response :error :message]))))}))]
    (r/create-class
     {:reagent-render
      (fn []
        [Segment
         [Header {:as "h4" :dividing true} "Enable Developer Account"]
         (when @enabled?
           [:div
            [:p "API Key: " [:b @(subscribe [:user/api-key])]]
            [:p "You can login at " [:a {:href "https://datasource.insilica.co" :target "_blank"} "datasource.insilica.co"] " using " [:b @(subscribe [:self/email])] " and your SysRev password."]])
         (when (not @enabled?)
           [:div
            [:p "A developer account allows full access to SysRev and Datasource, the underlying data backend. Create your own custom datasources and import them using the SysRev and Datasource GraphQL interface."]])
         [:div
          [:br]
          [:p "In addition to the SysRev and Datasource GraphQL interface, we provide an " [:a {:href "https://github.com/sysrev/RSysrev" :target "_blank"} "R library"] "."]
          [:br]]
         (when (and (not (plans-info/pro? (:nickname @plan)))
                    (not @enabled?))
           [:p [:b "Developer Accounts can only be activated by paid subscribers"]])
         [Radio {:toggle true
                 :id "enable-dev-account"
                 :label "Developer Account"
                 :checked @enabled?
                 :disabled (not (plans-info/pro? (:nickname @plan)))
                 :on-click (fn [_e] (toggle-dev-account! enabled?))}]
         [ui/CursorMessage error-message {:negative true}]])
      :get-initial-state (fn [_this]
                           (reset! loading? true)
                           nil)})))

(defn- UserSettings [{:keys [user-id]}]
  [Grid {:class "user-settings" :stackable true :columns 2}
   [Column
    [UserOptions]
    [PublicReviewerOptIn]
    [EnableDevAccount]]
   [Column [UserDevTools]]])

(def-panel :uri "/user/:user-id/settings" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (dispatch [:reload [:identity]])
              (dispatch [:set-active-panel panel]))
  :content [UserSettings]
  :require-login true)
