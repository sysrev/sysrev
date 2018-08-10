(ns sysrev.views.panels.login
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.base :refer [active-route]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.nav :refer [nav-scroll-top nav-redirect]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.ui :refer [get-panel-field]]
            [sysrev.util :refer
             [full-size? mobile? validate wrap-prevent-default nbsp]]
            [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private login-panel [:login])
(def ^:private register-panel [:register])

(def-data :register-project
  :loaded? (fn [db register-hash]
             ((comp not nil?)
              (get-panel-field db [:project register-hash] register-panel)))
  :uri (fn [_] "/api/query-register-project")
  :prereqs (fn [_] nil)
  :content (fn [register-hash] {:register-hash register-hash})
  :process
  (fn [_ [register-hash] {:keys [project]}]
    {:dispatch-n
     (list [:register/project-id register-hash (:project-id project)]
           [:register/project-name register-hash (:name project)])}))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6)
              "Password must be at least six characters"]})

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
        (list [::set-submitted]
              [::set-submitted-fields fields])}

       register?
       {:dispatch-n
        (list [::set-submitted]
              [::set-submitted-fields fields]
              [:action [:auth/register email password project-id]])}

       :else
       {:dispatch-n
        (list [::set-submitted]
              [::set-submitted-fields fields]
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
 :<- [::submitted-fields]
 :<- [::submitted]
 (fn [[fields sfields submitted]]
   (when submitted
     (let [fkeys (filter #(= (get fields %)
                             (get sfields %))
                         (keys fields))]
       (validate (select-keys sfields fkeys)
                 (select-keys login-validation fkeys))))))

(reg-sub
 ::submitted-fields
 :<- [:view-field :login [:submitted-fields]]
 (fn [fields] fields))

(reg-event-fx
 ::set-submitted-fields
 [trim-v]
 (fn [_ [fields]]
   {:dispatch [:set-view-field :login [:submitted-fields] fields]}))

(defn LoginRegisterPanel []
  (let [register? @(subscribe [::register?])
        project-id @(subscribe [:register/project-id])
        project-name @(subscribe [:register/project-name])
        register-hash @(subscribe [:register/register-hash])
        form-errors @(subscribe [::form-errors])
        fields @(subscribe [::fields])
        sfields @(subscribe [::submitted-fields])
        field-class #(if (get form-errors %) "error" "")
        field-error #(when-let [msg (get form-errors %)]
                       [:div.ui.warning.message msg])
        form-class (when-not (empty? form-errors) "warning")]
    (with-loader (if register-hash
                   [[:register-project register-hash]]
                   []) {}
      [:div.ui.segment.auto-margin.auth-segment
       {:id "login-register-panel"}
       (when register-hash
         [:h4.ui.header
          [:i.grey.list.alternate.outline.icon]
          [:div.content
           (if project-name project-name "< Project not found >")]])
       [:form.ui.form.login-register-form
        {:class form-class
         :on-submit
         (wrap-prevent-default
          #(let [email (email-input), password (password-input)]
             (dispatch [::submit-form {:email email
                                       :password password
                                       :register? register?
                                       :project-id project-id}])))}
        [:div.field.email {:class (field-class :email)}
         [:div.ui.left.icon.input
          [:i.user.icon]
          [:input {:type "email" :name "email"
                   :id "login-email-input"
                   :placeholder "E-mail address"
                   :on-change
                   #(dispatch-sync [::set-email (-> % .-target .-value)])}]]]
        [:div.field.password {:class (field-class :password)}
         [:div.ui.left.icon.input
          [:i.lock.icon]
          [:input {:type "password" :name "password"
                   :id "login-password-input"
                   :placeholder "Password"
                   :on-change
                   #(dispatch-sync [::set-password (-> % .-target .-value)])}]]]
        [field-error :email]
        [field-error :password]
        [:button.ui.fluid.primary.button
         {:type "submit" :name "submit"}
         (if register? "Register" "Login")]
        (when-let [err @(subscribe [::login-error-msg])]
          [:div.ui.negative.message err])]
       (if register?
         [:div.ui.center.aligned.grid
          [:div.column
           [:a.medium-weight {:href (if register-hash
                                      (str @active-route "/login")
                                      "/login")}
            "Already have an account?"]]]
         [:div.ui.two.column.center.aligned.grid
          [:div.column
           [:a.medium-weight {:href "/register"}
            "Create Account"]]
          [:div.column
           [:a.medium-weight {:href "/request-password-reset"}
            "Forgot Password?"]]])])))

(defn- wrap-join-project [& children]
  [:div.ui.padded.segments.auto-margin.join-project-panel
   (doall
    (map-indexed
     (fn [i e] ^{:key [:join-project i]} e)
     children))])

(defn join-project-panel []
  (let [register-hash @(subscribe [:register/register-hash])
        project-id @(subscribe [:register/project-id])
        project-name @(subscribe [:register/project-name])
        member? @(subscribe [:self/member? project-id])]
    (with-loader [[:register-project register-hash]] {}
      (cond
        (nil? project-id)
        [wrap-join-project
         [:h3.ui.center.aligned.header.segment
          "You have been invited to join:"]
         [:div.ui.center.aligned.segment
          [:h4.ui.header
           [:i.grey.list.alternate.outline.icon]
           [:div.content "< Project not found >"]]]]

        member?
        [wrap-join-project
         [:div.ui.segment
          [:h4.ui.header
           [:i.grey.list.alternate.outline.icon]
           [:div.content project-name]]]
         [:div.ui.center.aligned.segment
          [:h4 "You are already a member of this project."]]]

        :else
        [wrap-join-project
         [:h3.ui.center.aligned.header.segment
          "You have been invited to join:"]
         [:div.ui.segment
          [:h4.ui.header
           [:i.grey.list.alternate.outline.icon]
           [:div.content project-name]]]
         [:div.ui.center.aligned.segment
          [:button.ui.fluid.primary.button
           {:on-click #(dispatch [:action [:join-project project-id]])}
           "Join Project"]]]))))

(defmethod logged-out-content [:login] []
  [LoginRegisterPanel])

(defmethod logged-out-content [:register] []
  [LoginRegisterPanel])

(defmethod panel-content [:login] []
  (fn [child]
    (nav-redirect "/" :scroll-top? true)
    [:div]))

(defmethod panel-content [:register] []
  (fn [child]
    (if (and (nil? @(subscribe [:register/project-id]))
             (nil? @(subscribe [:register/register-hash])))
      (do (nav-redirect "/" :scroll-top? true)
          [:div])
      [join-project-panel])))
