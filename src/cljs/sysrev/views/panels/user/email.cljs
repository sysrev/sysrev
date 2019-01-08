(ns sysrev.views.panels.user.email
  (:require [ajax.core :refer [GET PUT]]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.semantic :refer [Segment Header Grid Row Column Label Button Message MessageHeader]]))

(def state (r/cursor app-db [:state :panels :user :email]))

(defn resend-verification-code
  []
  (let [resending-code? (r/cursor state [:code :resending?])
        resend-message (r/cursor state [:code :resend-messasge])
        resend-error (r/cursor state [:code :resend-error])]
    (reset! resending-code? true)
    (GET (str "/api/user/" @(subscribe [:self/user-id]) "/email/send-verification")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! resending-code? false)
                     (reset! resend-message "Confirmation email resent."))
          :error-handler (fn [error-response]
                           (reset! resending-code? false)
                           (reset! resend-error "There was an error sending the confirmation email."))})))
(defn Email
  []
  (let [resending-code? (r/cursor state [:code :resending?])
        resend-message (r/cursor state [:code :resend-messasge])
        resend-error (r/cursor state [:code :resend-error])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [email verified]} @(subscribe [:self/identity])]
          [Segment
           [:h4 email " " (if verified
                            [Label {:color "green"}
                             "Verified"]
                            [Label {:color "red"}
                             "Unverified"])
            " " (when (not verified) [Button {:size "mini"
                                              :basic true
                                              :on-click #(resend-verification-code)
                                              :disabled @resending-code?} "Resend Verification Email"])]
           (when-not (clojure.string/blank? @resend-error)
             [Message {:onDismiss #(reset! resend-error nil)
                       :negative true}
              @resend-error])
           (when-not (clojure.string/blank? @resend-message)
             [Message {:onDismiss #(reset! resend-message nil)
                       :positive true}
              @resend-message])]))
      :component-did-mount
      (fn [this]
        (reset! resend-error nil)
        (reset! resend-message nil)
        (dispatch [:fetch [:identity]]))})))

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
