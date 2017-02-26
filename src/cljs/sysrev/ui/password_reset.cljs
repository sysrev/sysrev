(ns sysrev.ui.password-reset
  (:require [sysrev.base :refer [st work-state]]
            [sysrev.ajax :as ajax]
            [sysrev.util :refer [validate]]
            [sysrev.state.core :as st :refer [data]])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(def pass-reset-validation
  {:password [#(>= (count %) 6) (str "Password must be at least six characters")]})

(def pass-reset-request-validation
  {:email [not-empty "Must enter an email address"]})

(defn request-password-reset-page []
  (let [page :request-password-reset
        validator #(validate (st :page page)
                             pass-reset-request-validation)
        loading? (and (true? (st :page page :submit))
                      (nil? (st :page page :sent)))
        on-submit (fn [event]
                    (using-work-state
                     (let [errors (validator)]
                       (swap! work-state assoc-in [:page page :submit] true)
                       (when (empty? errors)
                         (let [{:keys [email]} (st :page page)]
                           (ajax/post-request-password-reset email))))
                     (when (.-preventDefault event)
                       (.preventDefault event))
                     (set! (.-returnValue event) false)
                     false))
        input-change (fn [field-key]
                       #(using-work-state
                         (swap! work-state assoc-in [:page page :err] nil)
                         (swap! work-state assoc-in [:page page field-key]
                                (-> % .-target .-value))))
        validation (when (st :page page :submit) (validator))
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
          :value (st :page page :email)
          :on-change (input-change :email)}]]
       [error-msg :email]
       [:div.ui.divider]
       [:button.ui.button
        {:type "submit"
         :name "submit"
         :class (if loading? "loading" "")}
        "Submit"]
       (when-let [err (st :page page :err)]
         [:div.ui.negative.message err])
       (when (st :page page :sent)
         [:div.ui.green.message
          (str "An email has been sent with a link to reset your password.")])]]]))

(defn password-reset-page []
  (let [page :reset-password
        reset-code (st :page :reset-password :reset-code)
        validator #(validate (st :page page)
                             pass-reset-validation)
        on-submit (fn [event]
                    (using-work-state
                     (let [errors (validator)]
                       (swap! work-state assoc-in [:page page :submit] true)
                       (when (empty? errors)
                         (let [{:keys [email password]} (st :page page)]
                           (ajax/post-reset-password reset-code password))))
                     (when (.-preventDefault event)
                       (.preventDefault event))
                     (set! (.-returnValue event) false)
                     false))
        input-change (fn [field-key]
                       #(using-work-state
                         (swap! work-state assoc-in [:page page field-key]
                                (-> % .-target .-value))))
        validation (when (st :page page :submit) (validator))
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
          :value (data [:reset-code reset-code :email])
          :read-only true
          :auto-complete false}]]
       [:div.field {:class (error-class :password)}
        [:label "Enter new password"]
        [:input.ui.input
         {:type "password"
          :name "password"
          :value (st :page page :password)
          :on-change (input-change :password)
          :auto-complete false}]]
       [error-msg :email]
       [error-msg :password]
       [:div.ui.divider]
       [:button.ui.button {:type "submit" :name "submit"} "Submit"]
       (when-let [err (st :page page :err)]
         [:div.ui.negative.message err])]]]))
