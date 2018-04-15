(ns sysrev.views.panels.login
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.subs.ui :refer [get-panel-field]]
   [sysrev.routes :refer [nav-scroll-top]]
   [sysrev.base :refer [active-route]]
   [sysrev.util :refer
    [full-size? mobile? validate wrap-prevent-default nbsp]]
   [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private login-panel [:login])
(def ^:private register-panel [:register])

(defn have-register-project? [db register-hash]
  ((complement nil?)
   (get-panel-field db [:project register-hash] register-panel)))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6)
              (str "Password must be at least six characters")]})

(reg-event-fx
 :register/register-hash
 [trim-v]
 (fn [_ [register-hash]]
   {:dispatch [:set-panel-field [:transient :register-hash] register-hash register-panel]}))

(reg-sub
 :register/register-hash
 :<- [:panel-field [:transient :register-hash] register-panel]
 identity)

(reg-event-fx
 :register/project-id
 [trim-v]
 (fn [_ [register-hash project-id]]
   {:dispatch [:set-panel-field [:project register-hash :project-id] project-id register-panel]}))

(reg-sub-raw
 :register/project-id
 (fn [_ [_ register-hash]]
   (reaction
    (when-let [register-hash (or register-hash @(subscribe [:register/register-hash]))]
      @(subscribe [:panel-field [:project register-hash :project-id] register-panel])))))

(reg-event-fx
 :register/project-name
 [trim-v]
 (fn [_ [register-hash project-name]]
   {:dispatch [:set-panel-field [:project register-hash :name] project-name register-panel]}))

(reg-sub-raw
 :register/project-name
 (fn [_ [_ register-hash]]
   (reaction
    (when-let [register-hash (or register-hash @(subscribe [:register/register-hash]))]
      @(subscribe [:panel-field [:project register-hash :name] register-panel])))))

(reg-event-fx
 :register/login?
 [trim-v]
 (fn [_ [login?]]
   {:dispatch [:set-panel-field [:transient :login?] login? register-panel]}))

(reg-sub
 :register/login?
 :<- [:panel-field [:transient :login?] register-panel]
 identity)

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
 :<- [:active-panel]
 :<- [:register/login?]
 (fn [[panel login?]]
   (and (= panel register-panel) login?)))

(reg-sub
 ::register?
 (fn [_]
   [(subscribe [:active-panel])
    (subscribe [::login-to-join?])])
 (fn [[panel login-to-join?]]
   (and (= panel register-panel)
        (not login-to-join?))))

(reg-sub
 ::fields
 (fn [_]
   [(subscribe [::email])
    (subscribe [::password])])
 (fn [[email password]]
   {:email email :password password}))

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
 :<- [:register/register-hash]
 :<- [::register?]
 (fn [[register-hash register?]]
   (cond
     (and register?
          register-hash)  "Register for project"
     register?            "Register"
     :else                "Login")))

(reg-sub
 ::form-errors
 :<- [::fields]
 :<- [::submitted]
 (fn [[fields submitted]]
   (when submitted
     (validate fields login-validation))))

(defn LoginRegisterPanel []
  (let [register? @(subscribe [::register?])
        project-id @(subscribe [:register/project-id])
        project-name @(subscribe [:register/project-name])
        register-hash @(subscribe [:register/register-hash])
        form-errors @(subscribe [::form-errors])
        field-class #(if (get form-errors %) "error" "")
        field-error #(when-let [msg (get form-errors %)]
                       [:div.ui.warning.message msg])
        form-class (when-not (empty? form-errors) "warning")]
    (with-loader (if register-hash
                   [[:register-project register-hash]]
                   []) {}
      [:div.ui.segment.auto-margin
       {:id "login-register-panel"
        :style {:max-width "500px" :margin-top "10px"}}
       [:h3.ui.dividing.header
        @(subscribe [::header-title])]
       (when register-hash
         [:h4.ui.header
          (if project-name project-name "< Project not found >")])
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
                  #(dispatch-sync [::set-email (-> % .-target .-value)])}]]
        [:div.field {:class (field-class :password)}
         [:label "Password"]
         [:input {:type "password" :name "password"
                  :id "login-password-input"
                  :on-change
                  #(dispatch-sync [::set-password (-> % .-target .-value)])}]]
        [field-error :email]
        [field-error :password]
        [:div.ui.divider]
        [:button.ui.button {:type "submit" :name "submit"} "Submit"]
        (when-let [err @(subscribe [::login-error-msg])]
          [:div.ui.negative.message err])]
       [:div.ui.divider]
       [:div
        (if register?
          [:div
           [:a {:href (if register-hash
                        (str @active-route "/login")
                        "/login")}
            "Already have an account?"]]
          [:div
           [:a.medium-weight {:href "/register"}
            "Create Account"]
           nbsp nbsp nbsp "|" nbsp nbsp nbsp
           [:a.medium-weight {:href "/request-password-reset"}
            "Forgot Password?"]])]])))

(defn join-project-panel []
  (let [register-hash @(subscribe [:register/register-hash])
        project-id @(subscribe [:register/project-id])
        project-name @(subscribe [:register/project-name])
        member? @(subscribe [:self/member? project-id])]
    (with-loader [[:register-project register-hash]] {}
      (cond
        (nil? project-id)
        [:div.ui.padded.segments.auto-margin
         {:style {:max-width "550px" :margin-top "10px"}}
         [:div.ui.top.attached.segment
          [:h4 "You have been invited to join:"]]
         [:div.ui.bottom.attached.center.aligned.segment
          [:h4 "< Project not found >"]]]

        member?
        [:div.ui.padded.segments.auto-margin
         {:style {:max-width "550px" :margin-top "10px"}}
         [:div.ui.top.attached.segment
          [:h4 project-name]]
         [:div.ui.bottom.attached.center.aligned.segment
          [:h4 "You are already a member of this project."]]]

        :else
        [:div.ui.padded.segments.auto-margin
         {:style {:max-width "550px" :margin-top "10px"}}
         [:h4.ui.top.attached.center.aligned.header
          "You have been invited to join:"]
         [:div.ui.attached.segment
          [:h4 project-name]]
         [:div.ui.bottom.attached.center.aligned.segment
          [:button.ui.primary.button
           {:on-click #(dispatch [:action [:join-project project-id]])}
           "Join project"]]]))))

(defmethod logged-out-content [:login] []
  (fn [child]
    [LoginRegisterPanel]))

(defmethod logged-out-content [:register] []
  (fn [child]
    [LoginRegisterPanel]))

(defmethod panel-content [:login] []
  (fn [child]
    (nav-scroll-top "/")
    [:div]))

(defmethod panel-content [:register] []
  (fn [child]
    [join-project-panel]))
