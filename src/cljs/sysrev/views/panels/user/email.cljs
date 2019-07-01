(ns sysrev.views.panels.user.email
  (:require [clojure.string :as str]
            [ajax.core :refer [GET PUT POST DELETE]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :as s :refer
             [Grid Row Column Segment Header Message Button Label LabelDetail]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [->map-with-key parse-integer]])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [setup-panel-state sr-defroute with-loader]]))

(setup-panel-state panel [:user :email] {:state-var state
                                         :get-fn panel-get :set-fn panel-set
                                         :get-sub ::get :set-event ::set})

(defn get-email-addresses! []
  (let [email-addresses (r/cursor state [:email :addresses])]
    (GET (str "/api/user/" @(subscribe [:self/user-id]) "/email/addresses")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (reset! email-addresses (->map-with-key :id (:addresses result)))
                     (dispatch [:fetch [:identity]]))
          :error-handler (fn [response]
                           ($ js/console log "[get-email-addresses] There was an error"))})))

(defn resend-verification-code!
  [email-object]
  (let [{:keys [email id]} email-object
        resending-code? (r/cursor state [:code id :resending?])
        resend-message (r/cursor state [:code id :resend-messasge])
        resend-error (r/cursor state [:code id :resend-error])]
    (reset! resending-code? true)
    (PUT (str "/api/user/" @(subscribe [:self/user-id]) "/email/send-verification")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :params {:email email}
          :handler (fn [response]
                     (reset! resending-code? false)
                     (reset! resend-message "Confirmation email resent."))
          :error-handler (fn [error-response]
                           (reset! resending-code? false)
                           (reset! resend-error "There was an error sending the confirmation email."))})))

(defn create-email! [new-email]
  (let [sending-update? (r/cursor state [:update :sending?])
        update-message (r/cursor state [:update :message])
        update-error (r/cursor state [:update :error])
        adding-email? (r/cursor state [:email :changing?])]
    (reset! sending-update? true)
    (reset! update-message nil)
    (reset! update-error nil)
    (cond (str/blank? new-email)
          (do (reset! update-error "New email address can not be blank!")
              (reset! sending-update? false))
          :else
          (POST (str "/api/user/" @(subscribe [:self/user-id]) "/email")
                {:params {:email new-email}
                 :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                 :handler (fn [response]
                            (reset! sending-update? false)
                            (reset! adding-email? false)
                            (get-email-addresses!)
                            (reset! update-message "You've successfully added a new email address"))
                 :error-handler (fn [error-response]
                                  (reset! sending-update? false)
                                  (reset! update-error
                                          (get-in error-response [:response :error :message])))}))))

(defn delete-email!
  [email-object]
  (let [{:keys [email id]} email-object
        deleting-email? (r/cursor state [:email id :deleting?])
        delete-message (r/cursor state [:email id :delete-messasge])
        delete-error (r/cursor state [:email id :delete-error])]
    (reset! deleting-email? true)
    (DELETE (str "/api/user/" @(subscribe [:self/user-id]) "/email")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :params {:email email}
          :handler (fn [response]
                     (reset! deleting-email? false)
                     (get-email-addresses!))
          :error-handler (fn [error-response]
                           (reset! deleting-email? false)
                           (reset! delete-error (get-in error-response [:response :error :message]))
                           (reset! delete-error "There was an error deleting this emil."))})))

(defn set-primary!
  [email-object]
  (let [{:keys [email id]} email-object
        setting-primary? (r/cursor state [:email :setting-primary])
        set-primary-message (r/cursor state [:email id :set-primary-message])
        set-primary-error (r/cursor state [:email id :set-primary-error])]
    (reset! setting-primary? true)
    (PUT (str "/api/user/" @(subscribe [:self/user-id]) "/email/set-primary")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :params {:email email}
          :handler (fn [response]
                     (reset! setting-primary? false)
                     (get-email-addresses!))
          :error-handler (fn [error-response]
                           (reset! setting-primary? false)
                           (reset! set-primary-error
                                   (get-in error-response [:response :error :message])))})))

(defn EmailAddress
  [email-object]
  (let [{:keys [id]} email-object
        resending-code? (r/cursor state [:code id :resending?])
        resend-message (r/cursor state [:code id :resend-messasge])
        resend-error (r/cursor state [:code id :resend-error])
        deleting-email? (r/cursor state [:email id :deleting?])
        delete-error (r/cursor state [:email id :delete-error])
        setting-primary? (r/cursor state [:email :setting-primary])
        set-primary-error (r/cursor state [:email id :set-primary-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [email verified principal id]} @(r/cursor state [:email :addresses id])]
          [Grid {:class "user-email"}
           [Row {:class "user-email"}
            [Column {:class "user-email-info" :width 8}
             [:h4 {:class "email-entry"}
              email
              (when principal [Label {:size "small" :color "blue"} "Primary"])
              (if verified
                [Label {:class "email-verified" :size "small" :color "green"} "Verified"]
                [Label {:class "email-unverified" :size "small" :color "orange"} "Unverified"])]]
            [Column {:class "user-email-actions" :width 8 :align "right"}
             (when (not verified)
               [Button {:id "resend-verification-email"
                        :size "small"
                        :color "green"
                        :on-click #(resend-verification-code! email-object)
                        :disabled @resending-code?}
                "Resend Verification Email"])
             (when (not principal)
               [Button {:id "delete-email-button"
                        :size "small"
                        :color "orange"
                        :on-click #(delete-email! email-object)
                        :disabled @deleting-email?}
                "Delete Email"])
             (when (and verified (not principal))
               [Button {:id "make-primary-button"
                        :size "small"
                        :primary true
                        :on-click #(set-primary! email-object)
                        :disabled @setting-primary?}
                "Make Primary"])]]
           (when (some false? (mapv str/blank? [@resend-error
                                                @resend-message
                                                @delete-error
                                                @set-primary-error]))
             [Row
              [Column {:width 16}
               (when-not (str/blank? @resend-error)
                 [Message {:onDismiss #(reset! resend-error nil)
                           :negative true}
                  @resend-error])
               (when-not (str/blank? @resend-message)
                 [Message {:onDismiss #(reset! resend-message nil)
                           :positive true}
                  @resend-message])
               (when-not (str/blank? @delete-error)
                 [Message {:onDismiss #(reset! delete-error nil)
                           :negative true}
                  @delete-error])
               (when-not (str/blank? @set-primary-error)
                 [Message {:onDismiss #(reset! set-primary-error nil)
                           :negative true}
                  @set-primary-error])]])]))
      :component-did-mount
      (fn [this]
        (reset! resend-error nil)
        (reset! resend-message nil))})))

(defn CreateEmailAddress
  []
  (let [adding-email? (r/cursor state [:email :changing?])
        new-email (r/cursor state [:email :new-email])
        sending-update? (r/cursor state [:update :sending?])
        update-message (r/cursor state [:update :message])
        update-error (r/cursor state [:update :error])] 
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         (when-not @adding-email?
           [Button {:on-click #(do (reset! adding-email? true)
                                   (reset! update-error nil)
                                   (reset! update-message nil)
                                   (reset! new-email ""))}
            "Add a New Email Address"])
         (when @adding-email?
           [:div
            [s/Form {:on-submit #(do
                                   (reset! update-error nil)
                                   (reset! update-message nil)
                                   (create-email! @new-email))}
             [s/FormGroup
              [s/FormInput {:id "new-email-address"
                            :value @new-email
                            :width 8
                            :placeholder "New Email Address"
                            :on-change (fn [event]
                                         (reset! new-email ($ event :target.value)))}]
              [Button {:disabled @sending-update?
                       :id "new-email-address-submit"} "Submit"]
              [Button {:on-click (util/wrap-prevent-default
                                  #(do (reset! adding-email? false)
                                       (reset! update-error nil)
                                       (reset! update-message nil)))
                       :disabled @sending-update?} "Cancel"]]]
            (when-not (str/blank? @update-message)
              [Message {:onDismiss #(reset! update-message nil)
                        :positive true}
               @update-message])
            (when-not (str/blank? @update-error)
              [Message {:onDismiss #(reset! update-error nil)
                        :negative true}
               @update-error])])])
      :get-initial-state
      (fn [this]
        (reset! adding-email? false)
        (reset! sending-update? false)
        (reset! update-error "")
        (reset! new-email "")
        (reset! update-message "")
        nil)})))

(defn EmailAddresses
  []
  (r/create-class
   {:reagent-render (fn [this]
                      (let [email-addresses (vals @(r/cursor state [:email :addresses]))
                            primary-email-address (->> email-addresses
                                                       (filter :principal))
                            rest-email-addresses (->> email-addresses
                                                      (filter (comp not :principal))
                                                      (sort-by :email))
                            email-object-fn (fn [email-object]
                                              ^{:key (:id email-object)}
                                              [s/ListItem [EmailAddress email-object]])]
                        [s/ListUI {:divided true :relaxed true}
                         (when-not (empty? primary-email-address)
                           (doall (map email-object-fn primary-email-address)))
                         (when-not (empty? rest-email-addresses)
                           (doall (map email-object-fn rest-email-addresses)))
                         [s/ListItem [CreateEmailAddress]]]))
    :get-initial-state
    (fn [this]
      (get-email-addresses!))}))

(defn UserEmailSettings []
  [Segment
   [Header {:as "h4" :dividing true} "Email"]
   [EmailAddresses]])

(defmethod panel-content panel []
  (fn [child] [UserEmailSettings]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute user-email "/user/:user-id/email" [user-id]
             (let [user-id (parse-integer user-id)]
               (dispatch [:user-panel/set-user-id user-id])
               (get-email-addresses!)
               (dispatch [:set-active-panel panel])))
