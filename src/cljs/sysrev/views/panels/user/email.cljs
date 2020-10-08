(ns sysrev.views.panels.user.email
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.core :refer [def-data load-data]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.views.semantic :as S :refer
             [Grid Row Column Segment Header Button Label ListUI ListItem
              Form FormGroup FormInput]]
            [sysrev.util :as util :refer [index-by parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:user :email]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :user-emails/all
  :loaded?  (fn [db _] (-> (panel-get db)
                           (contains? :addresses)))
  :uri      (fn [self-id] (str "/api/user/" self-id "/email/addresses"))
  :process  (fn [{:keys [db]} _ {:keys [addresses]}]
              {:db (panel-set db :addresses (index-by :id addresses))
               :dispatch [:fetch [:identity]]}))

(def-action :user-emails/create
  :uri      (fn [self-id _] (str "/api/user/" self-id "/email"))
  :content  (fn [_ new-email] {:email new-email})
  :process  (fn [{:keys [db]} [self-id _] _]
              {:db (panel-set db [:create-email]
                              {:message "You've successfully added a new email address"
                               :open? false :error nil})
               :dispatch [:fetch [:user-emails/all self-id]]})
  :on-error (fn [{:keys [db error]} _ _]
              {:db (-> (panel-set db [:create-email :error] (:message error))
                       (panel-set    [:create-email :message] nil))}))

(def-action :user-emails/set-primary
  :method   :put
  :uri      (fn [self-id _ _] (str "/api/user/" self-id "/email/set-primary"))
  :content  (fn [_ email _] {:email email})
  :process  (fn [_ [self-id _ _] _]
              {:dispatch [:fetch [:user-emails/all self-id]]})
  :on-error (fn [{:keys [db error]} [_ _ id] _]
              {:db (panel-set db [:email id :set-primary-error] (:message error))}))

(def-action :user-emails/delete
  :method   :delete
  :uri      (fn [self-id _ _] (str "/api/user/" self-id "/email"))
  :content  (fn [_ email _] {:email email})
  :process  (fn [{:keys [db]} [self-id _ id] _]
              {:db (panel-set db [:email id :delete-error] nil)
               :dispatch [:fetch [:user-emails/all self-id]]})
  :on-error (fn [{:keys [db error]} [_ _ id] _]
              {:db (panel-set db [:email id :delete-error]
                              "There was an error deleting this email.")}))

(def-action :user-emails/send-verification
  :method   :put
  :uri      (fn [self-id _ _] (str "/api/user/" self-id "/email/send-verification"))
  :content  (fn [_ email _] {:email email})
  :process  (fn [{:keys [db]} [_ _ id] _]
              {:db (-> (panel-set db [:email id :resend-message] "Confirmation email resent.")
                       (panel-set    [:email id :resend-error] nil))})
  :on-error (fn [{:keys [db]} [_ _ id] _]
              {:db (-> (panel-set db [:email id :resend-message] nil)
                       (panel-set    [:email id :resend-error]
                                     "There was an error sending the confirmation email."))}))

(defn- EmailAddress [{:keys [id] :as _email-object}]
  (let [resend-message      (r/cursor state [:email id :resend-message])
        resend-error        (r/cursor state [:email id :resend-error])
        delete-error        (r/cursor state [:email id :delete-error])
        set-primary-error   (r/cursor state [:email id :set-primary-error])
        self-id             @(subscribe [:self/user-id])
        {:keys [email verified principal]} @(subscribe [::get [:addresses id]])]
    [Grid {:class "user-email" :vertical-align "middle"}
     [Row {:class "user-email" :vertical-align "middle"}
      [Column {:class "user-email-info" :width 8 :vertical-align "middle"}
       [:h4 {:class "email-entry"} email
        (when principal
          [Label {:size "small" :color "blue"} "Primary"])
        (if verified
          [Label {:class "email-verified" :size "small" :color "green"} "Verified"]
          [Label {:class "email-unverified" :size "small" :color "orange"} "Unverified"])]]
      [Column {:class "user-email-actions" :width 8 :vertical-align "middle" :align "right"}
       (when (not verified)
         [Button {:id "resend-verification-email" :size "small" :color "green"
                  :on-click #(run-action :user-emails/send-verification self-id email id)
                  :disabled (action/running? :user-emails/send-verification)}
          "Resend Verification Email"])
       (when (not principal)
         [Button {:id "delete-email-button" :size "small" :color "orange"
                  :on-click #(run-action :user-emails/delete self-id email id)
                  :disabled (action/running? :user-emails/delete)}
          "Delete Email"])
       (when (and verified (not principal))
         [Button {:id "make-primary-button" :size "small" :primary true
                  :on-click #(run-action :user-emails/set-primary self-id email id)
                  :disabled (action/running? :user-emails/set-primary)}
          "Make Primary"])]]
     (when (some seq [@resend-message @resend-error @delete-error @set-primary-error])
       [Row {:style {:padding-top "0" :margin-top 0 #_ "-0.1rem"}}
        [Column {:width 16}
         [CursorMessage resend-message {:positive true}]
         [CursorMessage resend-error {:negative true}]
         [CursorMessage delete-error {:negative true}]
         [CursorMessage set-primary-error {:negative true}]]])]))

(defn- CreateEmailAddress []
  (let [open?           (r/cursor state [:create-email :open?])
        new-email       (r/cursor state [:create-email :new-email])
        update-message  (r/cursor state [:create-email :message])
        update-error    (r/cursor state [:create-email :error])
        self-id         (subscribe [:self/user-id])
        running?        (action/running? :user-emails/create)]
    (if @open?
      ;; form is shown
      [:div
       [Form {:on-submit #(if (str/blank? (str @new-email))
                            (dispatch [::set [:create-email :error]
                                       "New email address can not be blank!"])
                            (run-action :user-emails/create @self-id @new-email))}
        [FormGroup
         [FormInput {:id "new-email-address"
                     :default-value (or @new-email "")
                     :width 8
                     :placeholder "New Email Address"
                     :on-change (util/on-event-value #(reset! new-email %))}]
         [Button {:id "new-email-address-submit" :type "submit"
                  :disabled running?}
          "Submit"]
         [Button {:on-click (util/wrap-user-event
                             #(dispatch [::set :create-email {:open? false}])
                             :prevent-default true)
                  :disabled running?}
          "Cancel"]]]
       [CursorMessage update-message {:positive true}]
       [CursorMessage update-error {:negative true}]]
      ;; form is hidden, button is shown
      [Button {:on-click (util/wrap-user-event
                          #(dispatch [::set :create-email {:open? true}]))}
       "Add a New Email Address"])))

(defn- EmailAddresses []
  (let [email-addresses        (vals @(r/cursor state [:addresses]))
        primary-email-address  (filter :principal email-addresses)
        other-email-addresses  (->> (remove :principal email-addresses)
                                    (sort-by :email))
        render-email           (fn [email-object] ^{:key (:id email-object)}
                                 [ListItem [EmailAddress email-object]])]
    [ListUI {:divided true :relaxed true}
     (doall (for [x primary-email-address] (render-email x)))
     (doall (for [x other-email-addresses] (render-email x)))
     [ListItem [CreateEmailAddress]]]))

(defn- Panel []
  [Segment
   [Header {:as "h4" :dividing true} "Email"]
   [EmailAddresses]])

(def-panel :uri "/user/:user-id/email" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [::set [] {}])
              (dispatch [:user-panel/set-user-id user-id])
              (load-data :user-emails/all user-id)
              (dispatch [:set-active-panel panel]))
  :content [Panel]
  :require-login true)
