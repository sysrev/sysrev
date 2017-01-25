(ns sysrev-web.ui.login
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.ajax :as ajax]
            [sysrev-web.util :refer [validate]]
            [sysrev-web.state.data :as d]))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6) (str "Password must be at least six characters")]})

(defn login-register-page
  [& [{:keys [register?]
       :or {register? false}}]]
  (let [page (if register? :register :login)
        project-hash (-> @state :page page :project-hash)
        project-id (and project-hash (d/project-id-from-hash project-hash))
        validator #(validate (-> @state :page page) login-validation)
        on-submit (fn [event]
                    (let [errors (validator)]
                      (swap! state assoc-in [:page page :submit] true)
                      (when (empty? errors)
                        (let [{:keys [email password]} (-> @state :page page)]
                          (if register?
                            (ajax/do-post-register email password project-id)
                            (ajax/do-post-login email password)))))
                    (when (.-preventDefault event)
                      (.preventDefault event))
                    (set! (.-returnValue event) false)
                    false)
        input-change (fn [field-key]
                       #(swap! state assoc-in [:page page field-key]
                               (-> % .-target .-value)))
        ;; recalculate the validation status if the submit button has been pressed
        validation (when (-> @state :page page :submit) (validator))
        error-class (fn [k] (when (k validation) "error"))
        error-msg (fn [k] (when (k validation) [:div.ui.warning.message (k validation)]))
        form-class (when-not (empty? validation) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:style {:max-width "550px" :margin-top "10px"}}
     [:h2.ui.top.attached.header
      (cond
        project-id "Register for project"
        register? "Register"
        :else "Login")]
     (when project-id
       [:div.ui.attached.segment
        [:h4 (str (d/data [:all-projects project-id :name]))]])
     [:div.ui.bottom.attached.segment
      [:form.ui.form {:class form-class :on-submit on-submit}
       [:div.field {:class (error-class :email)}
        [:label "Email"]
        [:input.ui.input
         {:type "email"
          :name "email"
          :value (-> @state :page page :email)
          :on-change (input-change :email)}]]
       [:div.field {:class (error-class :password)}
        [:label "Password"]
        [:input.ui.input
         {:type "password"
          :name "password"
          :value (-> @state :page page :password)
          :on-change (input-change :password)}]]
       [error-msg :email]
       [error-msg :password]
       [:div.ui.divider]
       [:button.ui.button {:type "submit" :name "submit"} "Submit"]
       (when-let [err (-> @state :page page :err)]
         [:div.ui.negative.message err])]
      [:div.ui.divider]
      (when (= page :register)
        [:div.center.aligned
         [:a {:href "/login"}
          "Already have an account?"]])
      (when (= page :login)
        [:div.center.aligned
         [:a {:href "/request-password-reset"}
          "Forgot password?"]])]]))
