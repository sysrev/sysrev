(ns sysrev.views.panels.password-reset
  (:require
   [re-frame.core :refer
    [subscribe dispatch dispatch-sync reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.subs.ui :refer [get-panel-field]]
   [sysrev.util :refer [validate wrap-prevent-default]]))

(def ^:private request-panel [:request-password-reset])
(def ^:private reset-panel [:reset-password])

(reg-event-fx
 ::request-email
 [trim-v]
 (fn [_ [email]]
   {:dispatch [:set-panel-field [:transient :email] email request-panel]}))

(reg-sub
 ::request-email
 :<- [:panel-field [:transient :email] request-panel]
 identity)

(reg-event-fx
 ::request-submitted?
 [trim-v]
 (fn [_ [submitted?]]
   {:dispatch [:set-panel-field [:transient :submitted?] submitted? request-panel]}))

(reg-sub
 ::request-submitted?
 :<- [:panel-field [:transient :submitted?] request-panel]
 identity)

(reg-event-fx
 :request-password-reset/sent?
 [trim-v]
 (fn [_ [sent?]]
   {:dispatch [:set-panel-field [:transient :sent?] sent? request-panel]}))

(reg-sub
 :request-password-reset/sent?
 :<- [:panel-field [:transient :sent?] request-panel]
 identity)

(reg-event-fx
 :request-password-reset/error
 [trim-v]
 (fn [_ [error]]
   {:dispatch [:set-panel-field [:transient :error] error request-panel]}))

(reg-sub
 :request-password-reset/error
 :<- [:panel-field [:transient :error] request-panel]
 identity)

(reg-event-fx
 :reset-password/reset-code
 [trim-v]
 (fn [_ [reset-code]]
   {:dispatch [:set-panel-field [:reset-code] reset-code reset-panel]}))

(reg-sub
 :reset-password/reset-code
 :<- [:panel-field [:reset-code] reset-panel]
 identity)

(reg-event-fx
 :reset-password/email
 [trim-v]
 (fn [_ [email]]
   {:dispatch [:set-panel-field [:email] email reset-panel]}))

(reg-sub
 :reset-password/email
 :<- [:panel-field [:email] reset-panel]
 identity)

(defn have-reset-code? [db reset-code]
  (let [active-code (get-panel-field db [:reset-code] reset-panel)
        active-email (get-panel-field db [:email] reset-panel)]
    (boolean
     (and active-email (= reset-code active-code)))))

(reg-event-fx
 ::reset-submitted?
 [trim-v]
 (fn [_ [submitted?]]
   {:dispatch [:set-panel-field [:transient :submitted?] submitted? reset-panel]}))

(reg-sub
 ::reset-submitted?
 :<- [:panel-field [:transient :submitted?] reset-panel]
 identity)

(reg-event-fx
 :reset-password/error
 [trim-v]
 (fn [_ [error]]
   {:dispatch [:set-panel-field [:transient :error] error reset-panel]}))

(reg-sub
 :reset-password/error
 :<- [:panel-field [:transient :error] reset-panel]
 identity)

(reg-event-fx
 :reset-password/success?
 [trim-v]
 (fn [_ [success?]]
   {:dispatch [:set-panel-field [:transient :success?] success? reset-panel]}))

(reg-sub
 :reset-password/success?
 :<- [:panel-field [:transient :success?] reset-panel]
 identity)

(reg-event-fx
 ::password
 [trim-v]
 (fn [_ [password]]
   {:dispatch [:set-panel-field [:transient :password] password reset-panel]}))

(reg-sub
 ::password
 :<- [:panel-field [:transient :password] reset-panel]
 identity)

(defn reset-password-panel []
  (let [reset-code @(subscribe [:reset-password/reset-code])
        email @(subscribe [:reset-password/email])
        submitted? @(subscribe [::reset-submitted?])
        password @(subscribe [::password])
        errors (when submitted?
                 (validate
                  {:password password}
                  {:password [#(>= (count %) 6) (str "Password must be at least six characters")]}))
        on-submit (wrap-prevent-default
                   #(when (and (not-empty email)
                               (not-empty password))
                      (do (dispatch [::reset-submitted? true])
                          (when (empty? errors)
                            (dispatch [:action [:auth/reset-password
                                                {:reset-code reset-code
                                                 :password password}]])))))
        on-password-change #(let [val (-> % .-target .-value)]
                              (dispatch-sync [::password val]))
        error-class #(when (get errors %) "error")
        error-msg #(when-let [msg (get errors %)]
                     [:div.ui.warning.message msg])
        form-class (when-not (empty? errors) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "500px" :margin-top "10px"}}
     [:h3.ui.top.attached.header
      "Reset Password"]
     [:div.ui.bottom.attached.segment
      (if (nil? email)
        (when-not @(subscribe [:any-loading?])
          [:h4 "Invalid reset code"])
        [:form.ui.form {:class form-class :on-submit on-submit
                        :autoComplete "off"}
         [:div.field
          [:label "Email"]
          [:input.ui.disabled.input
           {:type "email"
            :name "email"
            :value (or email "")
            :read-only true
            :autoComplete "off"}]]
         [:div.field {:class (error-class :password)}
          [:label "Enter new password"]
          [:input.ui.input
           {:type "password"
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
           [:div.ui.green.message
            "Password reset! Returning to login page..."])
         (when-let [msg @(subscribe [:reset-password/error])]
           [:div.ui.negative.message msg])])]]))

(defn request-password-reset-panel []
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
        on-email-change #(let [val (-> % .-target .-value)]
                           (dispatch-sync [::request-email val])
                           (dispatch-sync [:request-password-reset/error nil]))
        error-class #(when (get errors %) "error")
        error-msg #(when-let [msg (get errors %)]
                     [:div.ui.warning.message msg])
        form-class (when-not (empty? errors) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "500px" :margin-top "10px"}}
     [:h3.ui.top.attached.header
      "Request Password Reset"]
     [:div.ui.bottom.attached.segment
      [:form.ui.form {:class form-class :on-submit on-submit}
       [:div.field {:class (error-class :email)}
        [:label "Enter your email address"]
        [:input.ui.input
         {:type "email"
          :name "email"
          :value email
          :on-change on-email-change}]]
       [error-msg :email]
       [:div.ui.divider]
       [:button.ui.button
        {:type "submit"
         :name "submit"
         :class (if loading? "loading" "")}
        "Submit"]
       (when-let [msg @(subscribe [:request-password-reset/error])]
         [:div.ui.negative.message msg])
       (when @(subscribe [:request-password-reset/sent?])
         [:div.ui.green.message
          (str "An email has been sent with a link to reset your password.")])]]]))

(defmethod panel-content [:request-password-reset] []
  (fn [child]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "500px" :margin-top "10px"}}
     [:h3.ui.top.attached.header
      "Request Password Reset"]
     [:div.ui.bottom.attached.segment
      [:div.ui.orange.message
       "You must be logged out before using this."]]]))

(defmethod logged-out-content [:request-password-reset] []
  [request-password-reset-panel])

(defmethod panel-content [:reset-password] []
  (fn [child]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "500px" :margin-top "10px"}}
     [:h3.ui.top.attached.header
      "Reset Password"]
     [:div.ui.bottom.attached.segment
      [:div.ui.orange.message
       "You must be logged out before using this."]]]))

(defmethod logged-out-content [:reset-password] []
  [reset-password-panel])