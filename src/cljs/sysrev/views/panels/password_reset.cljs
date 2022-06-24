(ns sysrev.views.panels.password-reset
  (:require [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db trim-v]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.nav :as nav]
            [sysrev.state.ui :refer [get-panel-field set-panel-field]]
            [sysrev.util :refer [validate css wrap-prevent-default
                                 on-event-value]]
            [sysrev.macros :refer-macros [def-panel]]))

(def ^:private request-panel [:request-password-reset])
(def ^:private reset-panel [:reset-password])

(reg-event-db :reset-password/reset-code [trim-v]
              (fn [db [reset-code]]
                (set-panel-field db [:reset-code] reset-code reset-panel)))

(reg-sub :reset-password/reset-code
         :<- [:panel-field [:reset-code] reset-panel]
         identity)

(reg-event-db :reset-password/email [trim-v]
              (fn [db [email]]
                (set-panel-field db [:email] email reset-panel)))

(reg-sub :reset-password/email
         :<- [:panel-field [:email] reset-panel]
         identity)

(def-data :password-reset
  :loaded? (fn [db reset-code]
             (let [active-code (get-panel-field db [:reset-code] reset-panel)
                   active-email (get-panel-field db [:email] reset-panel)]
               (boolean
                (and active-email (= reset-code active-code)))))
  :uri (constantly "/api/auth/lookup-reset-code")
  :prereqs (constantly nil)
  :content (fn [reset-code] {:reset-code reset-code})
  :process (fn [_ [reset-code] {:keys [email]}]
             (when email
               {:dispatch-n (list [:reset-password/reset-code reset-code]
                                  [:reset-password/email email])})))

(def-action :auth/request-password-reset
  :uri (constantly "/api/auth/request-password-reset")
  :content (fn [email] {:email email :url-base (nav/current-url-base)})
  :process (fn [_ _ {:keys [success]}]
             (if success
               {:dispatch [:request-password-reset/sent? true]}
               {:dispatch-n (list [:request-password-reset/sent? false]
                                  [:request-password-reset/error
                                   "No account found for this email address."])})))

(def-action :auth/reset-password
  :uri (constantly "/api/auth/reset-password")
  :content (fn [{:keys [reset-code password] :as args}] args)
  :process
  (fn [_ _ {:keys [success message]}]
    (if success
      {:dispatch-n (list [:reset-password/success? true])
       :dispatch-later [{:ms 2000 :dispatch [:nav "/login"]}]}
      {:dispatch-n (list [:reset-password/error (or message "Request failed")])})))

(reg-event-db ::request-email [trim-v]
              (fn [db [email]]
                (set-panel-field db [:transient :email] email request-panel)))

(reg-sub ::request-email
         :<- [:panel-field [:transient :email] request-panel]
         identity)

(reg-event-db ::request-submitted? [trim-v]
              (fn [db [submitted?]]
                (set-panel-field db [:transient :submitted?] submitted? request-panel)))

(reg-sub ::request-submitted?
         :<- [:panel-field [:transient :submitted?] request-panel]
         identity)

(reg-event-db :request-password-reset/sent? [trim-v]
              (fn [db [sent?]]
                (set-panel-field db [:transient :sent?] sent? request-panel)))

(reg-sub :request-password-reset/sent?
         :<- [:panel-field [:transient :sent?] request-panel]
         identity)

(reg-event-db :request-password-reset/error [trim-v]
              (fn [db [error]]
                (set-panel-field db [:transient :error] error request-panel)))

(reg-sub :request-password-reset/error
         :<- [:panel-field [:transient :error] request-panel]
         identity)

(reg-event-db ::reset-submitted? [trim-v]
              (fn [db [submitted?]]
                (set-panel-field db [:transient :submitted?] submitted? reset-panel)))

(reg-sub ::reset-submitted?
         :<- [:panel-field [:transient :submitted?] reset-panel]
         identity)

(reg-event-db :reset-password/error [trim-v]
              (fn [db [error]]
                (set-panel-field db [:transient :error] error reset-panel)))

(reg-sub :reset-password/error
         :<- [:panel-field [:transient :error] reset-panel]
         identity)

(reg-event-db :reset-password/success? [trim-v]
              (fn [db [success?]]
                (set-panel-field db [:transient :success?] success? reset-panel)))

(reg-sub :reset-password/success?
         :<- [:panel-field [:transient :success?] reset-panel]
         identity)

(reg-event-db ::password [trim-v]
              (fn [db [password]]
                (set-panel-field db [:transient :password] password reset-panel)))

(reg-sub ::password
         :<- [:panel-field [:transient :password] reset-panel]
         identity)

(defn- reset-password-panel []
  (let [reset-code @(subscribe [:reset-password/reset-code])
        email @(subscribe [:reset-password/email])
        submitted? @(subscribe [::reset-submitted?])
        password @(subscribe [::password])
        errors (when submitted?
                 (validate {:password password}
                           {:password [#(>= (count %) 6)
                                       "Password must be at least six characters"]}))
        on-submit (wrap-prevent-default
                   #(when (and (not-empty email)
                               (not-empty password))
                      (dispatch [::reset-submitted? true])
                      (when (empty? errors)
                        (run-action :auth/reset-password {:reset-code reset-code
                                                          :password password}))))
        on-password-change (on-event-value #(dispatch-sync [::password %]))
        error-class #(when (get errors %) "error")
        error-msg #(when-let [msg (get errors %)]
                     [:div.ui.warning.message msg])
        form-class (when-not (empty? errors) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "500px" :margin-top "10px"}}
     [:h3.ui.top.attached.header "Reset Password"]
     [:div.ui.bottom.attached.segment
      (if (nil? email)
        (when-not (data/loading?)
          [:h4 "Invalid reset code"])
        [:form.ui.form {:class form-class :on-submit on-submit
                        :autoComplete "off"}
         [:div.field
          [:label "Email"]
          [:input.ui.disabled.input {:type "email"
                                     :name "email"
                                     :value (or email "")
                                     :read-only true
                                     :autoComplete "off"}]]
         [:div.field {:class (error-class :password)}
          [:label "Enter new password"]
          [:input.ui.input {:type "password"
                            :name "password"
                            :value (or password "")
                            :on-change on-password-change
                            :autoComplete "off"}]]
         [error-msg :password]
         [:div.ui.divider]
         [:button.ui.button {:type "submit" :name "submit"} "Submit"]
         (when-let [msg @(subscribe [:reset-password/error])]
           [:div.ui.negative.message msg])
         (when @(subscribe [:reset-password/success?])
           [:div.ui.green.message "Password reset! Returning to login page..."])
         (when-let [msg @(subscribe [:reset-password/error])]
           [:div.ui.negative.message msg])])]]))

(defn- request-password-reset-panel []
  (let [email @(subscribe [::request-email])
        submitted? @(subscribe [::request-submitted?])
        errors (when submitted?
                 (validate {:email email}
                           {:email [not-empty "Must enter an email address"]}))
        loading? (and (true? @(subscribe [::request-submitted?]))
                      (nil? @(subscribe [:request-password-reset/sent?])))
        on-submit (wrap-prevent-default
                   #(do (dispatch [::request-submitted? true])
                        (when (empty? errors)
                          (dispatch [:action [:auth/request-password-reset email]]))))
        on-email-change (on-event-value
                         #(do (dispatch-sync [::request-email %])
                              (dispatch-sync [:request-password-reset/error nil])))
        error-class #(when (get errors %) "error")
        error-msg #(when-let [msg (get errors %)]
                     [:div.ui.warning.message msg])
        form-class (when-not (empty? errors) "warning")]
    [:div.ui.segment.auto-margin.auth-segment
     [:form.ui.form {:class form-class :on-submit on-submit}
      [:div.field {:class (error-class :email)}
       [:div.ui.left.icon.input
        [:i.user.icon]
        [:input {:type "email"
                 :name "email"
                 :placeholder "E-mail address"
                 ;; :value email
                 :on-change on-email-change
                 :auto-focus true}]]]
      [error-msg :email]
      [:div.field
       [:button.ui.fluid.primary.button {:type "submit" :name "submit"
                                         :class (css [loading? "loading"])}
        "Send Password Reset Link"]]
      [:div.ui.center.aligned.grid.small>div.column
       [:a.medium-weight.small {:href "/login"} "Back to Login"]]
      (when-let [msg @(subscribe [:request-password-reset/error])]
        [:div.ui.negative.message msg])
      (when @(subscribe [:request-password-reset/sent?])
        [:div.ui.green.message
         "An email has been sent with a link to reset your password."])]]))

(def-panel :uri "/request-password-reset" :panel request-panel
  :on-route (dispatch [:set-active-panel [:request-password-reset]])
  :content [:div.ui.padded.segments.auto-margin
            {:style {:max-width "500px" :margin-top "10px"}}
            [:h3.ui.top.attached.header "Request Password Reset"]
            [:div.ui.bottom.attached.segment
             [:div.ui.orange.message "You must be logged out before using this."]]]
  :logged-out-content [request-password-reset-panel])

(def-panel :uri "/reset-password/:reset-code" :params [reset-code] :panel reset-panel
  :on-route (do (dispatch [:set-active-panel [:reset-password]])
                (dispatch [:reset-password/reset-code reset-code])
                (dispatch [:fetch [:password-reset reset-code]]))
  :content [:div.ui.padded.segments.auto-margin
            {:style {:max-width "500px" :margin-top "10px"}}
            [:h3.ui.top.attached.header "Reset Password"]
            [:div.ui.bottom.attached.segment
             [:div.ui.orange.message "You must be logged out before using this."]]]
  :logged-out-content [reset-password-panel])
