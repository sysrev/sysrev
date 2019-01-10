(ns sysrev.views.panels.user.email
  (:require [ajax.core :refer [GET PUT POST]]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.util :refer [vector->hash-map]]
            [sysrev.views.semantic :refer [Segment Header Grid Row Column Label Button Message MessageHeader ListUI Item
                                           FormGroup FormInput Form]])
  (:require-macros [reagent.interop :refer [$]]))

(def state (r/cursor app-db [:state :panels :user :email]))

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
                           (reset! verify-error (get-in error-response [:response :error :message])))})))


(defn get-email-addresses!
  []
  (let [retrieving-addresses? (r/cursor state [:email :retrieving-addresses?])
        email-addresses (r/cursor state [:email :addresses])]
    (reset! retrieving-addresses? true)
    (GET (str "/api/user/" @(subscribe [:self/user-id]) "/email/addresses")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-addresses? false)
                     (reset! email-addresses (-> response :result :addresses (vector->hash-map :id))))
          :error-handler (fn [error]
                           (reset! retrieving-addresses? false)
                           ($ js/console log "[get-email-addresses] There was an error"))})))

(defn VerifyEmail
  [code]
  (let [verify-message (r/cursor state [:code :verify-message])
        verify-error (r/cursor state [:code :verify-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         (when-not (clojure.string/blank? @verify-message)
           (js/setTimeout #(nav-scroll-top "/user/settings/email") 1000)
           [Message
            @verify-message])
         (when-not (clojure.string/blank? @verify-error)
           (js/setTimeout #(nav-scroll-top "/user/settings/email") 1000)
           [Message {:negative true}
            @verify-error])
         [:div {:style {:margin-top "1em"}}
          "Redirecting to email settings..."]])
      :get-initial-state
      (fn [this]
        (reset! verify-message nil)
        (reset! verify-error nil)
        (verify-email code))})))


(defn resend-verification-code!
  [email-address]
  (let [resending-code? (r/cursor state [:code :resending?])
        resend-message (r/cursor state [:code :resend-messasge])
        resend-error (r/cursor state [:code :resend-error])]
    (reset! resending-code? true)
    (PUT (str "/api/user/" @(subscribe [:self/user-id]) "/email/send-verification")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :params {:email email-address}
          :handler (fn [response]
                     (reset! resending-code? false)
                     (reset! resend-message "Confirmation email resent."))
          :error-handler (fn [error-response]
                           (reset! resending-code? false)
                           (reset! resend-error "There was an error sending the confirmation email."))})))
(defn EmailAddress
  [email-object]
  (let [resending-code? (r/cursor state [:code :resending?])
        resend-message (r/cursor state [:code :resend-messasge])
        resend-error (r/cursor state [:code :resend-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [email verified principal]} email-object]
          [Grid
           [Row
            [Column {:width 12}
             [:h4 email " " (if verified
                              [Label {:color "green"}
                               "Verified"]
                              [Label {:color "red"}
                               "Unverified"])
              (when principal
                [Label "Primary"])
              " " (when (not verified) [Button {:size "mini"
                                                :basic true
                                                :on-click #(resend-verification-code! email)
                                                :disabled @resending-code?} "Resend Verification Email"])]
             (when-not (clojure.string/blank? @resend-error)
               [Message {:onDismiss #(reset! resend-error nil)
                         :negative true}
                @resend-error])
             (when-not (clojure.string/blank? @resend-message)
               [Message {:onDismiss #(reset! resend-message nil)
                         :positive true}
                @resend-message])]
            (when-not principal
              [Column {:width 4}
               [Button {:size "mini"
                        :basic true
                        :on-click #(.log js/console "I don't do a whole lot")}
                "Delete Email"]])]]))
      :component-did-mount
      (fn [this]
        (reset! resend-error nil)
        (reset! resend-message nil)
        (dispatch [:fetch [:identity]]))})))

(defn create-email! [new-email]
  (let [sending-update? (r/cursor state [:update :sending?])
        update-message (r/cursor state [:update :message])
        update-error (r/cursor state [:update :error])
        adding-email? (r/cursor state [:email :changing?])]
    (reset! sending-update? true)
    (reset! update-message nil)
    (reset! update-error nil)
    (cond (clojure.string/blank? new-email)
          (do
            (reset! update-error "New email address can not be blank!")
            (reset! sending-update? false))
          :else
          (POST (str "/api/user/" @(subscribe [:self/user-id]) "/email/create")
                {:params {:email new-email}
                 :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                 :handler (fn [response]
                            (reset! sending-update? false)
                            (reset! adding-email? false)
                            (get-email-addresses!)
                            (reset! update-message "You've successfully added a new email address"))
                 :error-handler (fn [error-response]
                                  (reset! sending-update? false)
                                  (reset! update-error (get-in error-response [:response :error :message])))}))))

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
                                   (reset! update-message nil))}
            "Add a New Email Address"])
         (when @adding-email?
           [:div
            [Form {:on-submit #(do
                                 (reset! update-error nil)
                                 (reset! update-message nil)
                                 (create-email! @new-email)
                                 (reset! new-email nil))}
             [FormGroup
              [FormInput {:value @new-email
                          :width 8
                          :placeholder "New Email Address"
                          :on-change (fn [event]
                                       (reset! new-email ($ event :target.value)))}]
              [Button {:disabled @sending-update?} "Submit"]
              [Button {:on-click (fn [event]
                                   ($ event preventDefault)
                                   (reset! adding-email? false)
                                   (reset! update-error nil)
                                   (reset! update-message nil))
                       :disabled @sending-update?} "Cancel"]]]
            (when-not (clojure.string/blank? @update-message)
              [Message {:onDismiss #(reset! update-message nil)
                        :positive true}
               @update-message])
            (when-not (clojure.string/blank? @update-error)
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
  (let [email-addresses (r/cursor state [:email :addresses])]
    (r/create-class
     {:reagent-render (fn [this]
                        (when-not (empty? @email-addresses))
                        [ListUI {:divided true
                                 :relaxed true}
                         (doall (map
                                 (fn [email-object]
                                   ^{:key (:id email-object)}
                                   [Item [EmailAddress email-object]])
                                 (vals @email-addresses)))
                         [Item [CreateEmailAddress]]])
      :get-initial-state
      (fn [this]
        (get-email-addresses!))}
     )))

(defn EmailSettings
  []
  [Segment
   [Header {:as "h4"
            :dividing true}
    "Email"]
   [EmailAddresses]])
