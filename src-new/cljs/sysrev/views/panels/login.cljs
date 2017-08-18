(ns sysrev.views.panels.login
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.util :refer
    [full-size? mobile? validate wrap-prevent-default]]
   [sysrev.routes :refer [nav-scroll-top]]
   [sysrev.base :refer [active-route]]))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6)
              (str "Password must be at least six characters")]})

(reg-sub
 ::project-hash
 (fn [_]
   [(subscribe [:view-field :login [:project-hash]])])
 (fn [[project-hash]] project-hash))

(reg-sub
 ::email
 :<- [:view-field :login [:email]]
 (fn [email] (or email "")))

(reg-sub
 ::password
 :<- [:view-field :login [:password]]
 (fn [password] (or password "")))

(defn- email-input []
  (let [val (-> (js/$ "#login-email-input") (.val))]
    (dispatch [::set-email val])
    val))

(defn- password-input []
  (let [val (-> (js/$ "#login-password-input") (.val))]
    (dispatch [::set-password val])
    val))

(reg-sub
 ::submitted
 :<- [:view-field :login [:submitted]]
 (fn [submitted] submitted))

(reg-event-fx
 ::set-email
 [trim-v]
 (fn [_ [email]]
   {:dispatch [:set-view-field :login [:email] email]}))

(reg-event-fx
 ::set-password
 [trim-v]
 (fn [_ [password]]
   {:dispatch [:set-view-field :login [:password] password]}))

(reg-event-fx
 ::set-submitted
 [trim-v]
 (fn [_]
   {:dispatch [:set-view-field :login [:submitted] true]}))

(reg-sub
 ::login-to-join?
 (fn [_]
   [(subscribe [:active-panel])
    (subscribe [:panel-field [:login?]])])
 (fn [[panel login?]]
   (and (= panel :register) login?)))

(reg-sub
 ::register?
 (fn [_]
   [(subscribe [:active-panel])
    (subscribe [::login-to-join?])])
 (fn [[panel login-to-join?]]
   (and (= panel :register)
        (not login-to-join?))))

(reg-sub
 ::fields
 (fn [_]
   [(subscribe [::email])
    (subscribe [::password])])
 (fn [[email password]]
   {:email email :password password}))

(reg-sub
 ::register-project-id
 (fn [db]
   nil))

(reg-event-fx
 ::submit-form
 [trim-v]
 (fn [_ [{:keys [email password register? project-id]}]]
   (let [fields {:email email :password password}
         errors (validate fields login-validation)]
     (cond
       (or (empty? email) (empty? password))
       {}
       (not-empty errors)
       {:dispatch-n
        (list [::set-submitted])}
       register?
       {:dispatch-n
        (list [::set-submitted]
              [:action [:auth/register email password project-id]])}
       :else
       {:dispatch-n
        (list [::set-submitted]
              [:action [:auth/log-in email password]])}))))

(reg-sub
 ::login-error-msg
 :<- [:view-field :login [:err]]
 (fn [error-msg] error-msg))

(reg-event-fx
 :set-login-error-msg
 [trim-v]
 (fn [_ [error-msg]]
   {:dispatch [:set-view-field :login [:err] error-msg]}))

(reg-sub
 ::header-title
 :<- [::register-project-id]
 :<- [::register?]
 (fn [[project-id register?]]
   (cond
     project-id "Register for project"
     register? "Register"
     :else "Login")))

(reg-sub
 ::form-errors
 :<- [::fields]
 :<- [::submitted]
 (fn [[fields submitted]]
   (when submitted
     (validate fields login-validation))))

(defn login-register-panel []
  (let [register? @(subscribe [::register?])
        project-id @(subscribe [::register-project-id])
        form-errors @(subscribe [::form-errors])
        field-class #(if (get form-errors %) "error" "")
        field-error #(when-let [msg (get form-errors %)]
                       [:div.ui.warning.message msg])
        form-class (when-not (empty? form-errors) "warning")]
    [:div.ui.padded.segments.auto-margin
     {:id "login-register-panel"
      :style {:max-width "550px" :margin-top "10px"}}
     [:div.ui.top.attached.segment>h2
      @(subscribe [::header-title])]
     (when project-id
       [:div.ui.attached.segment>h4
        @(subscribe [:project/name project-id])])
     [:div.ui.bottom.attached.segment
      [:form.ui.form
       {:class form-class
        :on-submit
        (wrap-prevent-default
         #(let [email (email-input), password (password-input)]
            (dispatch [::submit-form {:email email
                                      :password password
                                      :register? register?
                                      :project-id project-id}])))}
       [:div.field {:class (field-class :email)}
        [:label "Email"]
        [:input {:type "email" :name "email"
                 :id "login-email-input"
                 :on-change
                 #(dispatch [::set-email (-> % .-target .-value)])}]]
       [:div.field {:class (field-class :password)}
        [:label "Password"]
        [:input {:type "password" :name "password"
                 :id "login-password-input"
                 :on-change
                 #(dispatch [::set-password (-> % .-target .-value)])}]]
       [field-error :email]
       [field-error :password]
       [:div.ui.divider]
       [:button.ui.button {:type "submit" :name "submit"} "Submit"]
       (when-let [err @(subscribe [::login-error-msg])]
         [:div.ui.negative.message err])]
      [:div.ui.divider]
      (if register?
        [:div.center.aligned
         [:a {:href (if project-id
                      (str @active-route "/login")
                      "/login")}
          "Already have an account?"]]
        [:div.center.aligned
         [:h4 "If you haven't created an account yet, please register using the link you received for your project."]
         [:div.ui.divider]
         [:div.center.aligned
          [:a {:href "/request-password-reset"}
           "Forgot password?"]]])]]))

(defmethod logged-out-content [:login] []
  [login-register-panel])
(defmethod logged-out-content [:register] []
  [login-register-panel])

(defmethod panel-content [:login] []
  (do (nav-scroll-top "/") [:div]))
(defmethod panel-content [:register] []
  [login-register-panel])
