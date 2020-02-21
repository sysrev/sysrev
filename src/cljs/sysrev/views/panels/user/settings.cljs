(ns sysrev.views.panels.user.settings
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.views.components.core :refer [selection-dropdown]]
            [ajax.core :refer [GET PUT]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :as sui :refer
             [Segment Header Grid Column Radio Message MessageHeader]]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:user :settings] {:state-var state
                                            :get-fn panel-get :set-fn panel-set
                                            :get-sub ::get :set-event ::set})

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
         {:on-click #(dispatch [:action [:user/delete-account user-id]])}
         "Delete Account"]]])))

(defn- PublicReviewerOptIn []
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
      (fn []
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
                     :on-change (fn [_e] (put-opt-in!))}]
             (when-not (str/blank? @error-message)
               [Message {:negative true, :onDismiss #(reset! error-message nil)}
                [MessageHeader "Opt-In Error"]
                @error-message])])))
      :get-initial-state
      (fn [_this]
        (reset! loading? true)
        (get-opt-in))})))

(defn UserSettings [{:keys [user-id]}]
  [Grid {:class "user-settings" :stackable true :columns 2}
   [Column [UserOptions]]
   [Column [UserDevTools]]
   [Column [PublicReviewerOptIn]]])

(defmethod panel-content panel []
  (fn [_child] [UserSettings]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute user-settings "/user/:user-id/settings" [user-id]
             (let [user-id (parse-integer user-id)]
               (dispatch [:user-panel/set-user-id user-id])
               (dispatch [:reload [:identity]])
               (dispatch [:set-active-panel panel])))
