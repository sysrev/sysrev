(ns sysrev.ui.password-reset
  (:require [sysrev.base :refer [state]]
            [sysrev.ajax :as ajax]
            [sysrev.util :refer [validate]]
            [sysrev.state.data :as d]))

(def pass-reset-validation
  {:password [#(>= (count %) 6) (str "Password must be at least six characters")]})

(def pass-reset-request-validation
  {:email [not-empty "Must enter an email address"]})

(defn request-password-reset-page []
  (let [page :request-password-reset
        validator #(validate (-> @state :page page)
                             pass-reset-request-validation)
        loading? (and (true? (-> @state :page page :submit))
                      (nil? (-> @state :page page :sent)))
        on-submit (fn [event]
                    (let [errors (validator)]
                      (swap! state assoc-in [:page page :submit] true)
                      (when (empty? errors)
                        (let [{:keys [email]} (-> @state :page page)]
                          (ajax/do-post-request-password-reset email))))
                    (when (.-preventDefault event)
                      (.preventDefault event))
                    (set! (.-returnValue event) false)
                    false)
        input-change (fn [field-key]
                       #(do
                          (swap! state assoc-in [:page page :err] nil)
                          (swap! state assoc-in [:page page field-key]
                                 (-> % .-target .-value))))
        validation (when (-> @state :page page :submit) (validator))
        error-class (fn [k] (when (k validation) "error"))
        error-msg (fn [k] (when (k validation)
                            [:div.ui.warning.message (k validation)]))
        form-class (when-not (empty? validation) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "450px" :margin-top "10px"}}
     [:h2.ui.top.attached.header
      "Request Password Reset"]
     [:div.ui.bottom.attached.segment
      [:form.ui.form {:class form-class :on-submit on-submit}
       [:div.field {:class (error-class :email)}
        [:label "Enter your email address"]
        [:input.ui.input
         {:type "email"
          :name "email"
          :value (-> @state :page page :email)
          :on-change (input-change :email)}]]
       [error-msg :email]
       [:div.ui.divider]
       [:button.ui.button
        {:type "submit"
         :name "submit"
         :class (if loading? "loading" "")}
        "Submit"]
       (when-let [err (-> @state :page page :err)]
         [:div.ui.negative.message err])
       (when (-> @state :page page :sent)
         [:div.ui.green.message
          (str "An email has been sent with a link to reset your password.")])]]]))

(defn password-reset-page []
  (let [page :reset-password
        reset-code (-> @ state :page :reset-password :reset-code)
        validator #(validate (-> @state :page page)
                             pass-reset-validation)
        on-submit (fn [event]
                    (let [errors (validator)]
                      (swap! state assoc-in [:page page :submit] true)
                      (when (empty? errors)
                        (let [{:keys [email password]} (-> @state :page page)]
                          (ajax/do-post-reset-password reset-code password))))
                    (when (.-preventDefault event)
                      (.preventDefault event))
                    (set! (.-returnValue event) false)
                    false)
        input-change (fn [field-key]
                       #(swap! state assoc-in [:page page field-key]
                               (-> % .-target .-value)))
        validation (when (-> @state :page page :submit) (validator))
        error-class (fn [k] (when (k validation) "error"))
        error-msg (fn [k] (when (k validation)
                            [:div.ui.warning.message (k validation)]))
        form-class (when-not (empty? validation) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "450px" :margin-top "10px"}}
     [:h2.ui.top.attached.header
      "Reset Password"]
     [:div.ui.bottom.attached.segment
      [:form.ui.form {:class form-class :on-submit on-submit}
       [:div.field {:class (error-class :email)}
        [:label "Email"]
        [:input.ui.disabled.input
         {:type "email"
          :name "email"
          :value (d/data [:reset-code reset-code :email])
          :read-only true
          :auto-complete false}]]
       [:div.field {:class (error-class :password)}
        [:label "Enter new password"]
        [:input.ui.input
         {:type "password"
          :name "password"
          :value (-> @state :page page :password)
          :on-change (input-change :password)
          :auto-complete false}]]
       [error-msg :email]
       [error-msg :password]
       [:div.ui.divider]
       [:button.ui.button {:type "submit" :name "submit"} "Submit"]
       (when-let [err (-> @state :page page :err)]
         [:div.ui.negative.message err])]]]))
