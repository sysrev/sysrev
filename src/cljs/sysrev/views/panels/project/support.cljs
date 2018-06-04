(ns sysrev.views.panels.project.support
  (:require [ajax.core :refer [GET]]
            [cljsjs.accounting]
            [cljsjs.semantic-ui-react]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.stripe :as stripe])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def panel [:project :project :support])

(def state (r/cursor app-db [:state :panels panel]))

(def semantic-ui js/semanticUIReact)
(def Form (r/adapt-react-class (goog.object/get semantic-ui "Form")))
(def FormButton (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Button)))
(def FormField (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Field)))
(def FormGroup (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Group)))
(def FormInput (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Input)))
(def FormRadio (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Radio)))
(def Label (r/adapt-react-class
            (goog.object/get semantic-ui "Label")))

;; functions around accounting.js
(defn unformat
  "Converts a string to a currency amount (default is in dollar)"
  [string]
  ($ js/accounting unformat string))

(defn to-fixed
  "Converts a number to a fixed value string to n decimal places"
  [number n]
  ($ js/accounting toFixed number n))

(defn string->cents
  "Convert a string to a number in cents"
  [string]
  (-> (to-fixed string 2)
      unformat
      (* 100)))

(defn cents->string
  "Convert a number to a USD currency string"
  [number]
  ($ js/accounting formatMoney (/ number 100)))

(def-action :support/support-plan
  :uri (fn [] "/api/support-project")
  :content (fn [amount]
             {:amount amount
              :project-id @(subscribe [:active-project-id])})
  :on-error (fn [{:keys [db error]} _ _]
              (cond
                (= (-> error :type) "invalid_request_error")
                (do
                  ;;(reset! (r/cursor state [:needs-payment-method?]) true)
                  (reset! (r/cursor state [:error-message])
                          "You must provide a valid payment method")
                  (reset! (r/cursor stripe/state [:need-card?]) true))
                :else
                (reset! (r/cursor state [:error-message]) (-> error :message)))
              {})
  :process
  (fn [{:keys [db]} _ {:keys [success] :as result}]
    ;;(.log js/console "I am also processing?!")
    (let [support-level (r/cursor state [:support-level])
          user-support-level (r/cursor state [:user-support-level])]
      (reset! (r/cursor state [:error-message]) nil)
      (reset! (r/cursor state [:confirm-message])
              (str "You are now supporting this project at "
                   (if (= @support-level :user-defined)
                     @user-support-level
                     (cents->string
                      @support-level))
                   " per month"))
      {})))

(defn get-current-support
  "Get the current support level"
  []
  (GET (str "/api/current-support")
       {:params {:project-id @(subscribe [:active-project-id])}
        :handler (fn [response]
                   (let [{:keys [quantity]} (:result response)
                         current-support-level (r/cursor state [:current-support-level])
                         support-level (r/cursor state [:support-level])
                         user-support-level (r/cursor state [:user-support-level])]
                     (reset! current-support-level quantity)
                     (cond
                       (some #(= @current-support-level
                                 %) [500 1000 5000])
                       (reset! support-level quantity)
                       ;; quantity is nil, but default something for support-level
                       (= @current-support-level
                          nil)
                       (do (reset! support-level 500))
                       :else (do
                               (reset! support-level :user-defined)
                               (reset! user-support-level quantity)))
                     (when-not (nil? @current-support-level)
                       (reset! (r/cursor state [:current-support-message])
                               (str "You are currently supporting this project at " (cents->string @current-support-level) " per month")))))}))

(defn SupportForm
  [state]
  (let [support-level (r/cursor state [:support-level])
        user-support-level (r/cursor state [:user-support-level])
        error-message (r/cursor state [:error-message])
        confirm-message (r/cursor state [:confirm-message])
        need-card? (r/cursor stripe/state [:need-card?])
        current-support-message (r/cursor state [:current-support-message])]
    (get-current-support)
    [:div.ui.segment [:h1 "Support This Project"]
     (when @confirm-message
       [:div.ui.green.header @confirm-message])
     (when @current-support-message
       [:h3 @current-support-message])
     [Form {:on-submit
            (fn []
              (let [cents (string->cents @user-support-level)]
                (cond (and (= @support-level
                              :user-defined)
                           (= cents
                              0))
                      (reset! user-support-level "$0.00")
                      (and (= @support-level
                              :user-defined)
                           (> cents
                              0))
                      (do
                        (dispatch [:action [:support/support-plan cents]])
                        ;;(.log js/console "[Supported at " cents "]")
                        )
                      :else
                      (do (dispatch [:action [:support/support-plan @support-level]])
                          ;;(.log js/console "[Supported at " @support-level "]")
                          ))))}
      [FormGroup
       [FormRadio {:label "$5 per month"
                   :checked (= @support-level
                               500)
                   :on-change #(reset! support-level 500)}]
       ;;[:p ""]
       ]
      [FormGroup
       [FormRadio {:label "$10 per month"
                   :checked (= @support-level
                               1000)
                   :on-change #(reset! support-level 1000)}]
       ;;[:p "Support this project at $10 per month"]
       ]
      [FormGroup
       [FormRadio {:label "$50 per month"
                   :checked (= @support-level
                               5000)
                   :on-change #(reset! support-level 5000)}]
       ;;[:p "Support this project at $50 per month"]
       ]
      [FormGroup
       [FormRadio {:checked (= @support-level
                               :user-defined)
                   :on-change #(reset! support-level :user-defined)}]
       [:div
        (let [on-change (fn [event]
                          (let [value ($ event :target.value)
                                dollar-sign-on-front? (fn [value]
                                                        (= ($ value indexOf "$")
                                                           0))
                                new-value (cond
                                            (not (dollar-sign-on-front? value))
                                            (str "$" ($ event :target.value))
                                            :else
                                            value)]
                            (reset! user-support-level new-value)))]
          [FormInput {:value @user-support-level
                      :on-change on-change
                      :on-click #(reset! support-level :user-defined)}]) " per month"]]
      (when-not @need-card?
        [FormButton "Continue"])
      (when @need-card?
        [:div {:class "ui button primary"
               :on-click (fn [e]
                           (.preventDefault e)
                           (dispatch [:payment/set-calling-route! (str "/p/" @(subscribe [:active-project-id]) "/support")])
                           (dispatch [:navigate [:payment]]))}
         "Update Payment Information"])
      (when @error-message
        [:div.ui.red.header @error-message])]]))

#_(defn RequestPaymentSource
    [state]
    [:div {:class "ui two columns stackable grid"}
     [:div {:class "column"}
      [:div {:class "ui segment secondary"}
       [:h1 "Please Provide a Payment Source"]
       [StripeCardInfo]]]])

(defn Support
  []
  (let [need-card? (r/cursor stripe/state [:need-card?])]
    [:div.panel
     [SupportForm state]]))

(defmethod panel-content panel []
  (fn [child]
    (reset! (r/cursor state [:user-support-level]) "$1.00")
    ;;(reset! (r/cursor state [:support-level]) 500)
    (reset! (r/cursor state [:error-message]) "")
    (reset! (r/cursor state [:confirm-message]) "")
    [Support]))
