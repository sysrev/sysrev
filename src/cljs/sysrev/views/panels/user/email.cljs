(ns sysrev.views.panels.user.email
  (:require [clojure.string :as str]
            [ajax.core :refer [GET PUT POST DELETE]]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.semantic :as s :refer
             [Grid Row Column Segment Header Message Button Label]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [->map-with-key]])
  (:require-macros [reagent.interop :refer [$]]))

(def panel [:user :email])

(def state (r/cursor app-db [:state :panels panel]))

(defn verify-email
  [code]
  (let [verifying-code? (r/cursor state [:code :verifying-code?])
        verify-message (r/cursor state [:code :verify-message])
        verify-error (r/cursor state [:code :verify-error])]
    (reset! verifying-code? true)
    (GET (str "/api/user/" @(subscribe [:self/user-id]) "/email/verify/" code)
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! verifying-code? false)
                     (reset! verify-message "Thank you for verifying your email address."))
          :error-handler (fn [error-response]
                           (reset! verifying-code? false)
                           (reset! verify-error (get-in error-response
                                                        [:response :error :message])))})))

(defn get-email-addresses!
  []
  (let [retrieving-addresses? (r/cursor state [:email :retrieving-addresses?])
        email-addresses (r/cursor state [:email :addresses])]
    (reset! retrieving-addresses? true)
    (GET (str "/api/user/" @(subscribe [:self/user-id]) "/email/addresses")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (reset! retrieving-addresses? false)
                     (dispatch [:fetch [:identity]])
                     (reset! email-addresses (->map-with-key :id (:addresses result))))
          :error-handler (fn [response]
                           (reset! retrieving-addresses? false)
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

(defn VerifyEmail
  [code]
  (let [verify-message (r/cursor state [:code :verify-message])
        verify-error (r/cursor state [:code :verify-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         (when-not (str/blank? @verify-message)
           (js/setTimeout #(nav-scroll-top (str "/user/" @(subscribe [:self/user-id]) "/email")) 1000)
           [Message @verify-message])
         (when-not (str/blank? @verify-error)
           (js/setTimeout #(nav-scroll-top (str "/user/" @(subscribe [:self/user-id]) "/email")) 1000)
           [Message {:negative true} @verify-error])
         [:div {:style {:margin-top "1em"}}
          "Redirecting to email settings..."]])
      :get-initial-state
      (fn [this]
        (reset! verify-message nil)
        (reset! verify-error nil)
        (verify-email code))})))

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
          [Grid
           [Row
            [Column {:width 11}
             [:h4 {:class "email-entry"}
              email " " (if verified
                          [Label {:color "green"} "Verified"]
                          [Label {:color "red"} "Unverified"])
              (when principal
                [Label "Primary"])
              " " (when (not verified)
                    [Button {:size "mini"
                             :basic true
                             :on-click #(resend-verification-code! email-object)
                             :disabled @resending-code?}
                     "Resend Verification Email"])]]
            [Column {:width 5}
             (when-not principal
               [:div
                [Button {:size "mini"
                         :id "delete-email-button"
                         :basic true
                         :on-click #(delete-email! email-object)
                         :disabled @deleting-email?}
                 "Delete Email"]
                (when verified
                  [Button {:size "mini"
                           :id "make-primary-button"
                           :basic true
                           :on-click #(set-primary! email-object)
                           :disabled @setting-primary?}
                   "Make Primary"])])]]
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

(defn EmailSettings
  []
  [Segment
   [Header {:as "h4" :dividing true} "Email"]
   [EmailAddresses]])
