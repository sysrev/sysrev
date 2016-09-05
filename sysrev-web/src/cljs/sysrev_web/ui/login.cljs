(ns sysrev-web.ui.login
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ajax :as ajax]
            [reagent.core :as r]
            [sysrev-web.util :refer [validate]]
            [sysrev-web.ui.components :refer [debug-box]]))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6) (str "Password must be at least six characters")]})

(def login-data (r/atom {:submit false}))

(defn login-form [on-submit]
  (let [validator #(validate @login-data login-validation)
        submit (fn []
                 (let [errors (validator)]
                   (swap! login-data assoc :submit true)
                   (when (empty? errors)
                     (on-submit (:email @login-data) (:password @login-data)))))
        input-change (fn [key]
                       (fn [e]
                         (swap! login-data assoc key (-> e .-target .-value))))
        ;; recalculate the validation status if the submit button has been pressed
        validation (when (:submit @login-data) (validator))
        error-class (fn [k] (when (k validation) "error"))
        error-msg (fn [k] (when (k validation) [:div.ui.warning.message (k validation)]))
        server-error (-> @state :page :login :err)
        form-class (when-not (empty? validation) "warning")]
    [:div.ui.form {:class form-class}
     [:div.ui.field {:class (error-class :email)}
      [:label "Email"]
      [:input.input {:type "text" :on-change (input-change :email)}]]
     [:div.ui.field {:class (error-class :password)}
      [:label "Password"]
      [:input {:type "password" :on-change (input-change :password)}]]
     [error-msg :user]
     [error-msg :password]
     [:div
      [:button.ui.primary.button {:type "button" :on-click submit} "Submit"]
      (when-not (empty? server-error)
        [:div.ui.negative.message  (-> @state :page :login :err)])]]))

(defn login-page []
  [:div.ui.padded.raised.segments.auto-margin
   {:style {:width "40%" :margin-top "15px"}}
   [:h2.ui.top.attached.header
    "Login"]
   [:div.ui.bottom.attached.segment
    [login-form ajax/do-post-login]]])

(defn register-page []
  [:div.ui.padded.raised.segments.auto-margin
   {:style {:width "40%" :margin-top "15px"}}
   [:h2.ui.top.attached.header
    "Register"]
   [:div.ui.bottom.attached.segment
    [login-form ajax/post-register]]])
