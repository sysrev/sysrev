(ns sysrev.stripe
  (:require [ajax.core :refer [POST GET]]
            [cljs-http.client :refer [generate-query-string]]
            [cljsjs.react-stripe-elements]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$]]
            [re-frame.core :refer [reg-sub subscribe dispatch reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :refer [nav-redirect get-url-params nav-scroll-top]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.views.semantic :as s]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [css]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

(setup-panel-state panel [:stripe] {:state-var state
                                    :get-fn panel-get
                                    :set-fn panel-set})

;; based on: https://github.com/stripe/react-stripe-elements
;;           https://jsfiddle.net/g9rm5qkt/

(def stripe-public-key
  (some-> (.getElementById js/document "stripe-public-key")
          (.getAttribute "data-stripe-public-key")))

(def stripe-client-id
  (some-> (.getElementById js/document "stripe-client-id")
          (.getAttribute "data-stripe-client-id")))

;; Stripe elements
(def Elements (r/adapt-react-class js/ReactStripeElements.Elements))
(def CardCVCElement (r/adapt-react-class js/ReactStripeElements.CardCVCElement))
(def CardElement (r/adapt-react-class js/ReactStripeElements.CardElement))
(def CardExpiryElement (r/adapt-react-class js/ReactStripeElements.CardExpiryElement))
(def CardNumberElement (r/adapt-react-class js/ReactStripeElements.CardNumberElement))
(def PostalCodeElement (r/adapt-react-class js/ReactStripeElements.PostalCodeElement))
(def StripeProvider (r/adapt-react-class js/ReactStripeElements.StripeProvider))

(def element-style {:base {:color "#424770"
                           :letterSpacing "0.025em"
                           :fontFamily "Source Code Pro, Menlo, monospace"
                           "::placeholder" {:color "#aab7c4"}}
                    :invalid {:color "#9e2146"}})

(reg-event-db :stripe/set-calling-route! [trim-v]
              (fn [db [calling-route]]
                (assoc-in db [:state :stripe :calling-route] calling-route)))

(def-action :stripe/add-payment-user
  :uri (fn [user-id token] (str "/api/user/" user-id "/stripe/payment-method"))
  :content (fn [user-id token] {:token token})
  :process (fn [{:keys [db]} [user-id _] _]
             (let [ ;; calling-route should be set by the :stripe/set-calling-route! event
                   ;; this is just in case it wasn't set
                   ;; e.g. user went directly to /payment route
                   calling-route (or (get-in db [:state :stripe :calling-route])
                                     (str "/user/" user-id "/billing"))]
               ;; clear any error message that was present in plans
               ;;(dispatch [:plans/clear-error-message!])
               { ;; don't need to ask for a card anymore
                :db (-> (panel-set db :need-card? false)
                        ;; clear any error message
                        (panel-set :error-message nil)
                        ;; reset the calling-route value upon redirect
                        (assoc-in [:state :stripe :calling-route] nil))
                ;; go back to where this panel was called from
                :nav-scroll-top calling-route}))
  :on-error (fn [{:keys [db error]} _ _]
              ;; we have an error message, set it so that it can be display to the user
              {:db (panel-set db :error-message (:message error))}))

(def-action :stripe/add-payment-org
  :uri (fn [org-id token] (str "/api/org/" org-id "/stripe/payment-method"))
  :content (fn [org-id token] {:token token})
  :process (fn [{:keys [db]} [org-id _] _]
             (let [calling-route (or (get-in db [:state :stripe :calling-route])
                                     (str "/org/" org-id "/billing"))]
               {:db (-> (panel-set db :need-card? false)
                        (panel-set :error-message nil)
                        (assoc-in [:state :stripe :calling-route] nil))
                :nav-scroll-top calling-route}))
  :on-error (fn [{:keys [db error]} _]
              {:db (panel-set db :error-message (:message error))}))

(def-data :user/default-source
  :uri (fn [user-id] (str "/api/user/" user-id "/stripe/default-source"))
  :loaded? (fn [db user-id]
             (-> (get-in db [:data :default-stripe-source :user])
                 (contains? user-id)))
  :process (fn [{:keys [db]} [user-id] {:keys [default-source]}]
             {:db (assoc-in db [:data :default-stripe-source :user user-id] default-source)}))

(reg-sub :user/default-source
         (fn [db [_ & [user-id]]]
           (let [user-id (or user-id (current-user-id db))]
             (get-in db [:data :default-stripe-source :user user-id]))))

(def-data :org/default-source
  :uri (fn [org-id] (str "/api/org/" org-id "/stripe/default-source"))
  :loaded? (fn [db org-id]
             (-> (get-in db [:data :default-stripe-source :org])
                 (contains? org-id)))
  :process (fn [{:keys [db]} [org-id] {:keys [default-source]}]
             {:db (assoc-in db [:data :default-stripe-source :org org-id] default-source)}))

(reg-sub :org/default-source
         (fn [db [_ org-id]]
           (get-in db [:data :default-stripe-source :org org-id])))

(defn inject-stripe [comp]
  (-> comp
      (r/reactify-component)
      (js/ReactStripeElements.injectStripe)
      (r/adapt-react-class)))

(defn StripeForm [{:keys [add-payment-fn]}]
  (inject-stripe
   (r/create-class
    {:display-name "stripe-reagent-form"
     :render
     (fn [this]
       (let [error-message (r/cursor state [:error-message])
             element-on-change #(let [{:keys [elementType error]}
                                      (js->clj % :keywordize-keys true)]
                                  ;; keeping the error for each element in the state atom
                                  (swap! (r/state-atom this)
                                         assoc (keyword elementType) error))
             errors? (fn []
                       ;; we're only putting errors in the state-atom,
                       ;; so this should be true only when there are errors
                       (not (every? nil? (vals @(r/state-atom this)))))]
         [:form
          {:class "StripeForm"
           :on-submit (util/wrap-prevent-default
                       #(when-not (errors?)
                          (-> (this.props.stripe.createToken)
                              (.then (fn [payload]
                                       (when-not (:error (js->clj payload :keywordize-keys true))
                                         (add-payment-fn payload)))))))}
          ;; In the case where the form elements themselves catch errors,
          ;; they are displayed below the input. Errors returned from the
          ;; server are shown below the submit button.
          [:label "Card Number"
           [CardNumberElement {:style element-style
                               :on-change element-on-change}]]
          [:div.ui.red.header
           @(r/cursor (r/state-atom this) [:cardNumber :message])]
          [:label "Expiration Date"
           [CardExpiryElement {:style element-style
                               :on-change element-on-change}]]
          [:div.ui.red.header
           @(r/cursor (r/state-atom this) [:cardExpiry :message])]
          [:label "CVC"
           [CardCVCElement {:style element-style
                            :on-change element-on-change}]]
          ;; this might not ever even generate an error, included here
          ;; for consistency
          [:div.ui.red.header
           @(r/cursor (r/state-atom this) [:cardCvc :message])]
          ;; unexplained behavior: Why do you need a minimum of
          ;; 6 digits to be entered in the cardNumber input for
          ;; in order for this to start checking input?
          [:label "Postal Code"
           [PostalCodeElement {:style element-style
                               :on-change element-on-change}]]
          [:div.ui.red.header
           @(r/cursor (r/state-atom this) [:postalCode :message])]
          [:button.ui.primary.button.use-card
           {:type "submit" :class (css [(errors?) "disabled"])}
           "Use Card"]
          ;; shows the errors returned from the server (our own, or stripe.com)
          (when @error-message
            [:div.ui.red.header @error-message])]))})))

(defn StripeCardInfo
  "add-payment-fn is a fn of payload returned by Stripe"
  [{:keys [add-payment-fn]}]
  [StripeProvider {:apiKey stripe-public-key}
   [Elements [(StripeForm {:add-payment-fn add-payment-fn})]]])

;; https://stripe.com/docs/connect/quickstart
;; use: phone number: 000-000-0000
;;      text confirm number: 000 000
;;      use debit card:  4000 0566 5566 5556
(defn ConnectWithStripe []
  (let [params {"client_id" stripe-client-id
                "response_type" "code"
                "redirect_uri" (str js/window.location.origin "/user/settings")
                "state" @(subscribe [:csrf-token])}]
    [s/Button {:href (str "https://connect.stripe.com/express/oauth/authorize?"
                          (generate-query-string params))}
     "Connect with Stripe"]))

(defn check-if-stripe-user! []
  (let [user-id @(subscribe [:self/user-id])
        connected? (r/cursor state [:connected?])
        retrieving-connected? (r/cursor state [:retrieving-connected?])]
    (reset! retrieving-connected? true)
    (GET (str "/api/stripe/connected/" user-id)
         {:response :json
          :handler (fn [{:keys [result]}]
                     (reset! retrieving-connected? false)
                     (reset! connected? (:connected result)))
          :error-handler (fn [{:keys [error]}]
                           (reset! retrieving-connected? false)
                           ($ js/console log "[[sysrev.strip/check-if-stripe-user!]] error"))})))

(defn save-stripe-user! [user-id stripe-code]
  (POST "/api/stripe/finalize-user"
        {:params {:user-id user-id, :stripe-code stripe-code}
         :headers {"x-csrf-token" @(subscribe [:csrf-token])}
         :handler (fn [_] (check-if-stripe-user!))
         :error-handler (fn [_] ($ js/console log "[[sysrev.stripe/save-stripe-user]] error"))}))
