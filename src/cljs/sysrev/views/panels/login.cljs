(ns sysrev.views.panels.login
  (:require [reagent.core :as r]
            [goog.uri.utils :as uri-utils]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw reg-event-fx trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.base :as base :refer [active-route]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.ui :refer [get-panel-field]]
            [sysrev.views.semantic :as S]
            [sysrev.util :as util :refer [css validate wrap-prevent-default nbsp on-event-value]]
            [sysrev.macros :refer-macros [with-loader def-panel]]))

(def register-panel [:register])

(def-data :invite-code-info
  :loaded? (fn [db invite-code]
             (not (and (nil? (get-panel-field db [:project invite-code] register-panel))
                       (nil? (get-panel-field db [:org invite-code] register-panel)))))
  :uri (fn [_] "/api/invite-code-info")
  :prereqs (fn [_] nil)
  :content (fn [invite-code] {:invite-code invite-code})
  :process
  (fn [_ [invite-code] {:keys [project org]}]
    (if project
      {:dispatch-n
       (list [:register/project-id invite-code (:project-id project)]
             [:register/project-name invite-code (:name project)])}
      {:dispatch-n
       (list [:register/org-id invite-code (:org-id org)]
             [:register/org-name invite-code (:name org)])})))

(def-action :nav-google-login
  :method   :get
  :uri      "/api/auth/google-oauth-url"
  :content  (fn [register? redirect]
              {:base-url (nav/current-url-base)
               :register (if register? "true" "false")
               :redirect redirect})
  :process  (fn [_ _ oauth-url]
              (when (string? oauth-url)
                (nav/load-url oauth-url :absolute true))))

(def login-validation
  {:email [not-empty "Must enter an email address"]
   :password [#(>= (count %) 6)
              "Password must be at least six characters"]})

(reg-event-fx :register/invite-code [trim-v]
              (fn [_ [invite-code]]
                {:dispatch [:set-panel-field [:transient :invite-code] invite-code register-panel]}))

(reg-sub :register/invite-code
         :<- [:panel-field [:transient :invite-code] register-panel]
         identity)

(reg-event-fx :register/project-id [trim-v]
              (fn [_ [invite-code project-id]]
                {:dispatch [:set-panel-field [:project invite-code :project-id]
                            project-id register-panel]}))

(reg-sub-raw :register/project-id
             (fn [_ [_ invite-code]]
               (reaction
                (when-let [hash (or invite-code @(subscribe [:register/invite-code]))]
                  @(subscribe [:panel-field [:project hash :project-id]
                               register-panel])))))

(reg-event-fx :register/project-name [trim-v]
              (fn [_ [invite-code project-name]]
                {:dispatch [:set-panel-field [:project invite-code :name]
                            project-name register-panel]}))

(reg-sub-raw :register/project-name
             (fn [_ [_ invite-code]]
               (reaction
                (when-let [hash (or invite-code @(subscribe [:register/invite-code]))]
                  @(subscribe [:panel-field [:project hash :name] register-panel])))))

(reg-event-fx :register/org-id [trim-v]
              (fn [_ [invite-code org-id]]
                {:dispatch [:set-panel-field [:org invite-code :org-id]
                            org-id register-panel]}))

(reg-sub-raw :register/org-id
             (fn [_ [_ invite-code]]
               (reaction
                (when-let [hash (or invite-code @(subscribe [:register/invite-code]))]
                  @(subscribe [:panel-field [:org hash :org-id]
                               register-panel])))))

(reg-event-fx :register/org-name [trim-v]
              (fn [_ [invite-code org-name]]
                {:dispatch [:set-panel-field [:org invite-code :name]
                            org-name register-panel]}))

(reg-sub-raw :register/org-name
             (fn [_ [_ invite-code]]
               (reaction
                (when-let [hash (or invite-code @(subscribe [:register/invite-code]))]
                  @(subscribe [:panel-field [:org hash :name] register-panel])))))


(reg-event-fx :register/login? [trim-v]
              (fn [_ [login?]]
                {:dispatch [:set-panel-field [:transient :login?] login? register-panel]}))

(reg-sub :register/login?
         :<- [:panel-field [:transient :login?] register-panel]
         identity)

(reg-sub ::email
         :<- [:view-field :login [:email]]
         #(or % ""))

(reg-sub ::password
         :<- [:view-field :login [:password]]
         #(or % ""))

(defn- email-input []
  (let [val (.-value (js/document.getElementById "login-email-input"))]
    (dispatch [::set-email val])
    val))

(defn- password-input []
  (let [val (.-value (js/document.getElementById "login-password-input"))]
    (dispatch [::set-password val])
    val))

(reg-sub ::submitted
         :<- [:view-field :login [:submitted]]
         identity)

(reg-event-fx ::set-email [trim-v]
              (fn [_ [email]]
                {:dispatch [:set-view-field :login [:email] email]}))

(reg-event-fx ::set-password [trim-v]
              (fn [_ [password]]
                {:dispatch [:set-view-field :login [:password] password]}))

(reg-event-fx ::set-submitted [trim-v]
              (fn [_]
                {:dispatch [:set-view-field :login [:submitted] true]}))

(reg-sub ::login-to-join?
         :<- [:active-panel]
         :<- [:register/login?]
         (fn [[panel login?]]
           (and (= panel register-panel) login?)))

(reg-sub ::register?
         :<- [:active-panel]
         :<- [::login-to-join?]
         :<- [:landing-page?]
         (fn [[panel login-to-join? landing?]]
           (or landing? (and (= panel register-panel)
                             (not login-to-join?)))))

(reg-sub ::fields
         :<- [::email]
         :<- [::password]
         (fn [[email password]]
           {:email email :password password}))

(reg-event-fx ::submit-form [trim-v]
              (fn [_ [{:keys [email password register? project-id org-id redirect]}]]
                (let [fields {:email email :password password}
                      errors (validate fields login-validation)]
                  (cond (or (empty? email) (empty? password))
                        {}
                        (not-empty errors)
                        {:dispatch-n
                         (list [::set-submitted]
                               [::set-submitted-fields fields])}
                        register?
                        {:dispatch-n
                         (list [::set-submitted]
                               [::set-submitted-fields fields]
                               [:action [:auth/register email password project-id org-id redirect]])}
                        :else
                        {:dispatch-n
                         (list [::set-submitted]
                               [::set-submitted-fields fields]
                               [:action [:auth/log-in email password redirect]])}))))

(reg-sub ::login-error-msg
         :<- [:view-field :login [:err]]
         identity)

(reg-event-fx :set-login-error-msg [trim-v]
              (fn [_ [error-msg]]
                {:dispatch [:set-view-field :login [:err] error-msg]}))

(reg-sub ::form-errors
         :<- [::fields]
         :<- [::submitted-fields]
         :<- [::submitted]
         (fn [[fields sfields submitted]]
           (when submitted
             (let [fkeys (filter #(= (get fields %) (get sfields %))
                                 (keys fields))]
               (validate (select-keys sfields fkeys)
                         (select-keys login-validation fkeys))))))

(reg-sub ::submitted-fields
         :<- [:view-field :login [:submitted-fields]]
         identity)

(reg-event-fx ::set-submitted-fields [trim-v]
              (fn [_ [fields]]
                {:dispatch [:set-view-field :login [:submitted-fields] fields]}))

(defn- GoogleLogInImage [img-type]
  (let [theme (if @(subscribe [:self/dark-theme?])
                "dark" "light")]
    [S/Image {:class (str "basic label "
                          "google-" img-type " "
                          "google-" theme)
              :src (str "/images/google_signin/btn_google_" "dark" "_"
                        img-type "_ios.svg")}]))

;; TODO implement redirect behavior for google login
(defn GoogleLogInButton [{:keys [type redirect] :or {type "login"}}]
  [S/Button {:id (if (= type "login")
                   "google-login-button"
                   "google-signup-button")
             :as "div"
             :on-click (wrap-prevent-default
                        #(run-action :nav-google-login (= type "register") redirect))
             :label-position "left"
             :style {:margin-top "0.5em" :margin-left "auto" :margin-right "auto"}}
   [GoogleLogInImage "normal"]
   [GoogleLogInImage "focus"]
   [GoogleLogInImage "pressed"]
   [S/Button {:primary true :fluid true}
    (if (= type "login")
      "Sign in with Google"
      "Sign up with Google")]])

(defn LoginRegisterPanel []
  (let [landing? @(subscribe [:landing-page?])
        register? @(subscribe [::register?])
        project-id @(subscribe [:register/project-id])
        project-name @(subscribe [:register/project-name])
        org-id @(subscribe [:register/org-id])
        org-name @(subscribe [:register/org-name])
        object-name (or project-name org-name)
        invite-code @(subscribe [:register/invite-code])
        form-errors @(subscribe [::form-errors])
        field-class #(if (get form-errors %) "error" "")
        field-error #(when-let [msg (get form-errors %)]
                       [:div.ui.warning.message msg])
        form-class (when-not (empty? form-errors) "warning")
        redirect-param (uri-utils/getParamValue @active-route "redirect")
        redirect-message (uri-utils/getParamValue @active-route "redirect_message")
        state-redirect @(subscribe [:login-redirect-url])
        redirect (or redirect-param state-redirect)
        _dark? @(subscribe [:self/dark-theme?])]
    (with-loader (if invite-code
                   [[:invite-code-info invite-code]]
                   []) {}
      [:div
       [:h3 {:style {:text-align "center"}}
        redirect-message]
       [:div.ui.center.aligned.segment.auto-margin.auth-segment
        {:id "login-register-panel"}
        (when invite-code
          [:h4.ui.header
           [:i.grey.list.alternate.outline.icon]
           [:div.content
            (if object-name object-name "< Project not found >")]])
        [:form.ui.form.login-register-form
         {:class form-class
          :on-submit
          (wrap-prevent-default
           #(let [email (email-input), password (password-input)]
              (dispatch [::submit-form {:email email
                                        :password password
                                        :register? register?
                                        :project-id project-id
                                        :org-id org-id
                                        :redirect redirect}])))}
         [:h1 {:style {:margin-top 5 :font-size "48px"}}
          (if register? "Try Sysrev for Free" "Log In to Sysrev")]
         (when register? [:h2 {:style {:margin-top 5 :font-size "20px"}}
                          "Start your systematic review."])

         [:div.field.email {:class (field-class :email)}
          [:div.ui.left.icon.input
           [:i.user.icon]
           [:input {:auto-focus true
                    :type "email" :name "email"
                    :id "login-email-input"
                    :placeholder "E-mail address"
                    :on-change (on-event-value #(dispatch-sync [::set-email %]))}]]]
         [:div.field.password {:class (field-class :password)}
          [:div.ui.left.icon.input
           [:i.lock.icon]
           [:input {:type "password" :name "password"
                    :id "login-password-input"
                    :placeholder "Password"
                    :on-change (on-event-value #(dispatch-sync [::set-password %]))}]]]
         [field-error :email]
         [field-error :password]
         [:button.ui.fluid.primary.button
          {:id :login-submit-button
           :type "submit" :name "submit"
           :class (css [(and register? (action/running? :auth/register))
                        "loading"])}
          (cond landing?   "Sign Up"
                register?  "Register"
                :else      "Log in")]
         (when-let [err @(subscribe [::login-error-msg])]
           [:div.ui.negative.message err])
         [:div [:div.ui.divider]
          [GoogleLogInButton {:type (if register? "register" "login")
                              :redirect redirect}]]
         (let [{:keys [auth-error auth-email]} (util/get-url-params)]
           (when auth-error
             [:div.ui.negative.message
              [:div.content
               (case auth-error
                 "sysrev-login"  (str "Account for "
                                      (if auth-email auth-email "Google login")
                                      " not found")
                 "google-login"  (str "Google login was unsuccessful")
                 "sysrev-signup" (str "Failed to create new account")
                 "google-signup" (str "Google signup was unsuccessful")
                 (if (string? auth-error)
                   auth-error
                   "An unexpected error occurred"))]]))]
        [:div.ui.divider {:style {:margin-top "0.25em"}}]
        (cond landing?   nil
              register?  [:div.ui.center.aligned.grid
                          [:div.column
                           [:a.medium-weight {:href (if invite-code
                                                      (str @active-route "/login")
                                                      "/login")}
                            "Already have an account?"]]]
              :else      [:div.ui.two.column.center.aligned.grid
                          [:div.column
                           [:a.medium-weight {:href "/register"}
                            "Create Account"]]
                          [:div.column
                           [:a.medium-weight {:href "/request-password-reset"}
                            "Forgot Password?"]]])]])))

(defn- wrap-join-project [& children]
  (into [:div.ui.padded.segments.auto-margin.join-project-panel]
        children))

(defn invite-not-found []
  [wrap-join-project
   [:div.ui.center.aligned.segment
    [:h4.ui.header
     [:i.grey.list.alternate.outline.icon]
     [:div.content "Invite not found"]]]])

(defn join-segment [{:keys [button-content error name on-click]}]
  [wrap-join-project
   [:h3.ui.center.aligned.header.segment
    "You have been invited to join:"]
   [:div.ui.segment
    [:h4.ui.header
     [:i.grey.list.alternate.outline.icon]
     [:div.content name]]]
   (when @error
     [:div.ui.center.segment
      {:style {:color "red"}}
      [:p @error]])
   [:div.ui.center.aligned.segment
    [:button.ui.fluid.primary.button
     {:on-click on-click}
     button-content]]])

(defn join-org-panel [org-id invite-code]
  (r/with-let [error (r/atom nil)]
    [join-segment
     {:button-content "Join Organization"
      :error error
      :name @(subscribe [:register/org-name])
      :on-click #(dispatch [:action [:org/join org-id invite-code error]])}]))

(defn join-project-panel [project-id invite-code]
  (r/with-let [redirecting? (atom nil)
               error (r/atom nil)]
    (if @(subscribe [:self/member? project-id])
      (do (when-not @redirecting?
            (js/setTimeout #(nav/nav (project-uri project-id)) 1000)
            (js/setTimeout #(reset! redirecting? true) 0))
          [wrap-join-project
           [:div.ui.center.aligned.segment
            [:h4 "You are already a member of this project."]
            [:h5 {:style {:margin-top "1em"}}
             "Redirecting... " nbsp nbsp nbsp
             [:div.ui.small.active.inline.loader]]]])
      [join-segment
       {:button-content "Join Project"
        :error          error
        :name           @(subscribe [:register/project-name])
        :on-click       #(dispatch [:action [:join-project invite-code]])}])))

(defn- redirect-root-content []
  (nav/nav "/")
  [:div])

(defn- register-logged-in-content []
  (let [org-id @(subscribe [:register/org-id])
        project-id @(subscribe [:register/project-id])
        invite-code @(subscribe [:register/invite-code])]
    (with-loader [[:invite-code-info invite-code]] {}
      (cond
        org-id [join-org-panel org-id invite-code]
        project-id [join-project-panel project-id invite-code]
        invite-code [invite-not-found]
        :else [redirect-root-content]))))

(def-panel :uri "/login" :panel [:login]
  :on-route (dispatch [:set-active-panel [:login]])
  :content [redirect-root-content]
  :logged-out-content [LoginRegisterPanel])

(def-panel :uri "/register" :panel [:register]
  :on-route (dispatch [:set-active-panel [:register]])
  :content [register-logged-in-content]
  :logged-out-content [LoginRegisterPanel])

(def-panel :uri "/register/:invite-code" :params [invite-code]
  :on-route (do (dispatch [:set-active-panel [:register]])
                (dispatch [:register/invite-code invite-code])))

(def-panel :uri "/register/:invite-code/login" :params [invite-code]
  :on-route (do (dispatch [:set-active-panel [:register]])
                (dispatch [:register/invite-code invite-code])
                (dispatch [:register/login? true])
                (dispatch [:set-login-redirect-url
                           (str "/register/" invite-code)])))
