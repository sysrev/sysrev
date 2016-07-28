(ns sysrev-web.ui.login
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.react.components :refer [link]]
            [sysrev-web.routes :as routes]
            [reagent.core :as r]
            [sysrev-web.forms.validate :refer [validate]]
            [sysrev-web.base :refer [debug-box]]))

(def login-validation {:username [not-empty "Must provide user"]
                       :password [#(> (count %) 6) (str "Password must be greater than six characters")]})

(defn login
  "login takes a handler, which will be called with the map {:user username :password password}"
  [handler]
  (let [form-data (r/atom {:submit false})
        validator #(validate @form-data login-validation)
        submit (fn []
                 (swap! form-data assoc :submit true)
                 (when (empty? (validator)) (handler @form-data)))
        input-change (fn [key] (fn [e] (swap! form-data assoc key (-> e .-target .-value))))]
    (fn []
      ; recalculate the validation status if the submit button has been pressed
      (let [validation (when (:submit @form-data) (validator))
            error-class (fn [k] (when (k validation) "error"))
            error-msg (fn [k] (when (k validation) [:div.ui.warning.message (k validation)]))
            form-class (when-not (empty? validation) "warning")]
        [:div.ui.form {:class form-class}
         [:div.ui.field {:class (error-class :username)}
          [:label "Email"]
          [:input.input {:type "text" :value (:username @form-data) :on-change (input-change :username)}]]
         [:div.ui.field {:class (error-class :password)}
          [:label "Password"]
          [:input {:type "password" :value (:password @form-data) :on-change (input-change :password)}]]
         [error-msg :user]
         [error-msg :password]
         [:button.ui.primary.button {:type "button" :on-click submit} "Submit"]]))))
