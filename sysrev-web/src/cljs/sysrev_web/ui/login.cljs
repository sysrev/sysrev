(ns sysrev-web.ui.login
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.ajax :as ajax]
            [reagent.core :as r]
            [sysrev-web.util :refer [validate]]
            [sysrev-web.ui.components :refer [debug-box]]))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6) (str "Password must be at least six characters")]})

(defn login-register-page [is-register?]
  (let [page (if is-register? :register :login)
        validator #(validate (-> @state :page page) login-validation)
        on-submit (fn []
                    (let [errors (validator)]
                      (swap! state assoc-in [:page page :submit] true)
                      (when (empty? errors)
                        (let [{:keys [email password]} (-> @state :page page)]
                          (if is-register?
                            (ajax/do-post-register email password)
                            (ajax/do-post-login email password))))))
        input-change (fn [field-key]
                       #(swap! state assoc-in [:page page field-key]
                               (-> % .-target .-value)))
        ;; recalculate the validation status if the submit button has been pressed
        validation (when (-> @state :page page :submit) (validator))
        error-class (fn [k] (when (k validation) "error"))
        error-msg (fn [k] (when (k validation) [:div.ui.warning.message (k validation)]))
        form-class (when-not (empty? validation) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "450px" :margin-top "10px"}}
     [:h2.ui.top.attached.header
      (if is-register? "Register" "Login")]
     [:div.ui.bottom.attached.segment
      [:div.ui.form {:class form-class}
       [:div.ui.field {:class (error-class :email)}
        [:label "Email"]
        [:input.input {:type "text"
                       :value (-> @state :page page :email)
                       :on-change (input-change :email)}]]
       [:div.ui.field {:class (error-class :password)}
        [:label "Password"]
        [:input {:type "password"
                 :value (-> @state :page page :password)
                 :on-change (input-change :password)}]]
       [error-msg :user]
       [error-msg :password]
       [:div
        [:button.ui.primary.button {:type "button" :on-click on-submit} "Submit"]
        (when-let [err (-> @state :page page :err)]
          [:div.ui.negative.message err])]]]]))
