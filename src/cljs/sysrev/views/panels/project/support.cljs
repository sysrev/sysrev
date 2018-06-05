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
                       ;; the support level is at a fixed amount
                       (some #(= @current-support-level
                                 %) [500 1000 5000])
                       (do
                         (reset! support-level quantity)
                         (reset! user-support-level (cents->string 100)))

                       ;; the support level is at variable amount
                       (not (nil? @current-support-level))
                       (do
                         (reset! support-level :user-defined)
                         (reset! user-support-level (cents->string quantity)))
                       ;; quantity is nil, but default something for support-level
                       ;; but only if the support-level is currently nil
                       (and (nil? @current-support-level)
                            (nil? @support-level))
                       (do (reset! support-level 500)))
                     (when-not (nil? @current-support-level)
                       (reset! (r/cursor state [:current-support-message])
                               (str "You are currently supporting this project at " (cents->string @current-support-level) " per month")))))}))

(def-action :support/support-plan
  :uri (fn [] "/api/support-project")
  :content (fn [amount]
             {:amount amount
              :project-id @(subscribe [:active-project-id])})
  :on-error (fn [{:keys [db error]} _ _]
              (cond
                (= (-> error :type) "invalid_request_error")
                (do
                  (reset! (r/cursor state [:error-message])
                          "You must provide a valid payment method")
                  (reset! (r/cursor stripe/state [:need-card?]) true))
                (= (-> error :type) "already_supported_at_amount")
                (reset! (r/cursor state [:error-message])
                        (str "You are already supporting at "
                             (cents->string (-> error :amount))))
                :else
                (reset! (r/cursor state [:error-message]) (-> error :message)))
              (reset! (r/cursor state [:support-loading?]) false)
              {})
  :process
  (fn [{:keys [db]} _ {:keys [success] :as result}]
    (let [support-level (r/cursor state [:support-level])
          user-support-level (r/cursor state [:user-support-level])]
      (reset! (r/cursor state [:support-loading?]) false)
      (reset! (r/cursor state [:error-message]) nil)
      (get-current-support)
      {})))

(defn SupportForm
  [state]
  (let [support-level (r/cursor state [:support-level])
        user-support-level (r/cursor state [:user-support-level])
        error-message (r/cursor state [:error-message])
        need-card? (r/cursor stripe/state [:need-card?])
        support-loading? (r/cursor state [:support-loading?])
        current-support-message (r/cursor state [:current-support-message])]
    [:div.ui.segment [:h1 "Support This Project"]
     (when @current-support-message
       [:h3.support-message @current-support-message])
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
                        (reset! support-loading? true)
                        (dispatch [:action [:support/support-plan cents]]))
                      :else
                      (do
                        (reset! support-loading? true)
                        (dispatch [:action [:support/support-plan @support-level]])))))}
      [FormGroup
       [FormRadio {:label "$5 per month"
                   :checked (= @support-level
                               500)
                   :on-change #(reset! support-level 500)}]]
      [FormGroup
       [FormRadio {:label "$10 per month"
                   :checked (= @support-level
                               1000)
                   :on-change #(reset! support-level 1000)}]]
      [FormGroup
       [FormRadio {:label "$50 per month"
                   :checked (= @support-level
                               5000)
                   :on-change #(reset! support-level 5000)}]]
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
        [:div.field [:button {:class (str "ui button primary "
                                          (when @support-loading?
                                            " disabled"))}
                     "Continue"]])
      (when @need-card?
        [:div {:class "ui button primary"
               :on-click (fn [e]
                           (.preventDefault e)
                           (dispatch [:payment/set-calling-route! (str "/p/" @(subscribe [:active-project-id]) "/support")])
                           (dispatch [:navigate [:payment]]))}
         "Update Payment Information"])
      (when @error-message
        [:div.ui.red.header @error-message])]]))

(defn Support
  []
  (let [need-card? (r/cursor stripe/state [:need-card?])]
    (get-current-support)
    [:div.panel
     [SupportForm state]]))

(defmethod panel-content panel []
  (fn [child]
    (reset! (r/cursor state [:user-support-level]) "$1.00")
    (reset! (r/cursor state [:error-message]) "")
    (reset! (r/cursor state [:confirm-message]) "")
    (reset! (r/cursor state [:support-loading?]) false)
    [Support]))
