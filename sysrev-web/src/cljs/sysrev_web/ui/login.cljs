(ns sysrev-web.ui.login
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ajax :as ajax]
            [reagent.core :as r]
            [sysrev-web.util :refer [validate]]
            [sysrev-web.ui.components :refer [debug-box]]))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6) (str "Password must be at least six characters")]})

(defn login-form [on-submit]
  (let [form-data (r/atom {:submit false})
        validator #(validate @form-data login-validation)
        submit (fn []
                 (let [errors (validator)]
                   (swap! form-data assoc :submit true)
                   (when (empty? errors)
                     (on-submit (:email @form-data) (:password @form-data)))))
        input-change (fn [key]
                       (fn [e]
                         (swap! form-data assoc key (-> e .-target .-value))))]
    (fn []
      ;; recalculate the validation status if the submit button has been pressed
      (let [validation (when (:submit @form-data) (validator))
            error-class (fn [k] (when (k validation) "error"))
            error-msg (fn [k] (when (k validation) [:div.ui.warning.message (k validation)]))
            form-class (when-not (empty? validation) "warning")]
        [:div.ui.form {:class form-class}
         [:div.ui.field {:class (error-class :email)}
          [:label "Email"]
          [:input.input {:type "text" :value (:email @form-data) :on-change (input-change :email)}]]
         [:div.ui.field {:class (error-class :password)}
          [:label "Password"]
          [:input {:type "password" :value (:password @form-data) :on-change (input-change :password)}]]
         [error-msg :user]
         [error-msg :password]
         [:button.ui.primary.button {:type "button" :on-click submit} "Submit"]]))))

(defn login-page []
  [:div.ui.padded.raised.segments.auto-margin
   {:style {:width "40%" :margin-top "15px"}}
   [:h2.ui.top.attached.header
    "Login"]
   [:div.ui.bottom.attached.segment
    [login-form ajax/post-login]]])

(defn register-page []
  [:div.ui.padded.raised.segments.auto-margin
   {:style {:width "40%" :margin-top "15px"}}
   [:h2.ui.top.attached.header
    "Register"]
   [:div.ui.bottom.attached.segment
    [login-form ajax/post-register]]])
