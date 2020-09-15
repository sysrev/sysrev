(ns sysrev.paypal
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.accounting :as acct]
            [sysrev.views.semantic :as s]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state with-mount-body]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:paypal] {:state-var state
                                    :get-fn panel-get :set-fn panel-set
                                    :get-sub ::get :set-event ::set})

(def minimum-amount "$1")
(def ^:unused paypal-env
  (some-> (.getElementById js/document "paypal-env")
          (.getAttribute "data-paypal-env")))

(def ^:unused paypal-client-id
  (some-> (.getElementById js/document "paypal-client-id")
          (.getAttribute "data-paypal-client-id")))

(def-action :paypal/add-funds
  :uri (constantly "/api/paypal/add-funds")
  :content (fn [project-id user-id order-id]
             {:project-id project-id :user-id user-id :order-id order-id})
  :process (fn [{:keys [db]} _ _result]
             (let [amount (panel-get db :user-defined-support-level)]
               {:db (-> (panel-set db :success-message
                                   (str "Your payment of $" amount " has been received and processed"))
                        (panel-set :error-message nil)
                        (panel-set :user-defined-support-level nil))
                :dispatch [:project/get-funds]}))
  :on-error (fn [{:keys [db error]} [_ _ _] _]
              {:db (-> (panel-set db :success-message nil)
                       (panel-set :error-message (:message error)))}))

;; depends on https://www.paypalobjects.com/api/checkout.js (loaded in index.clj)
;; https://developer.paypal.com/docs/checkout/how-to/customize-flow/#interactive-code-demo
;; https://developer.paypal.com/docs/checkout/how-to/customize-flow/#show-a-confirmation-page

;; This will have to be refactored to use:
;; https://developer.paypal.com/docs/checkout/integrate/
;;
(defn PayPalButton
  "PayPal button component. on-authorize and on-error are optional functions
  to run additionally in PayPal authorize and error hooks."
  [project-id user-id amount-ref & {:keys [on-authorize on-error]}]
  [(with-mount-body
     (-> (js/paypal.Buttons
          (clj->js
           {:style {:label "pay"
                    :size "responsive"
                    :height 38
                    :shape "rect"
                    :color "gold"
                    :tagline false
                    :fundingicons false}
            :createOrder
            (fn [^js _data ^js actions]
              (->> {:purchase_units [{:amount {:value (acct/format-money @amount-ref "")}}]}
                   (clj->js)
                   (.order.create actions)))
            :onApprove
            (fn [^js data ^js actions]
              (-> (.order.capture actions)
                  (.then (fn [_details]
                           (dispatch [:action [:paypal/add-funds project-id
                                               user-id (.-orderID data)]])))))
            :onError
            (fn [err]
              (js/console.error "PayPalButton error")
              (dispatch [::set :error-message
                         "An error was encountered during PayPal checkout."])
              (when on-error (on-error err))
              nil)
            ;; hack from https://github.com/paypal/paypal-checkout-components/issues/1158
            :onClick
            (fn [_data actions]
              (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 500)))
                  (.then (.reject actions))))}))
         (.render "#paypal-button")))
   [:div#paypal-button]])

(defn amount-validation [amount]
  (let [amount (some-> (util/ensure-pred string? amount)
                       not-empty
                       (util/ensure-prefix "$"))]
    (cond (empty? amount)                                  :empty
          (not (re-matches acct/valid-usd-regex amount))   :invalid-amount
          (< (acct/string->cents amount)
             (acct/string->cents minimum-amount))          :minimum-amount)))

(defn AddFundsImpl [& {:keys [on-success]}]
  (let [set-val #(dispatch-sync [::set %1 %2])
        set-amount #(set-val :user-defined-support-level %)
        amount-ref (r/cursor state [:user-defined-support-level])
        amount @amount-ref
        disabled? (boolean (or (empty? amount)
                               (amount-validation amount)))]
    [:div
     [:h4.ui.dividing.header "Add Funds"]
     [:div.ui.form {:on-submit util/no-submit}
      [:div.fields {:style {:margin-bottom "0"}}
       [s/FormField {:width 8 :error (boolean (and (not-empty amount)
                                                   (amount-validation amount)))}
        [:div.ui.labeled.input
         [:div.ui.label "$"]
         [:input
          {:id "paypal-amount" :type "text" :autoComplete "off"
           :value amount
           :placeholder "Amount"
           :on-change (util/on-event-value
                       #(let [error (amount-validation %)]
                          (set-amount %)
                          (set-val :error-message
                                   (condp = error
                                     :minimum-amount (str "Minimum amount is "
                                                          (acct/format-money minimum-amount "$"))
                                     :invalid-amount "Amount is not valid"
                                     nil))))}]]]
       (let [project-id @(subscribe [:active-project-id])
             user-id @(subscribe [:self/user-id])]
         ;; the built-in PayPal disabled button is weird, the buttons
         ;; simply aren't clickable. This reproduces the same effect
         ;; much simpler
         [s/FormField {:width 8 :disabled disabled?
                       :style (when disabled? {:pointer-events "none"})}
          [PayPalButton project-id user-id amount-ref :on-authorize on-success]])]
      (when-let [msg @(subscribe [::get :error-message])]
        [s/Message {:negative true} msg])
      (when-let [msg @(subscribe [::get :success-message])]
        [s/Message {:positive true}
         [s/MessageHeader "Payment Processed"] msg])]]))

(defn AddFunds [& {:keys [on-success]}]
  (let [set-val #(dispatch-sync [::set %1 %2])
        set-amount #(set-val :user-defined-support-level %)]
    (r/create-class
     {:reagent-render (fn [& {:keys [on-success]}]
                        [AddFundsImpl :on-success on-success])
      :get-initial-state (fn [_this]
                           (set-val :support-level :user-defined)
                           (set-amount nil)
                           (set-val :error-message nil)
                           (set-val :success-message nil)
                           {})})))
