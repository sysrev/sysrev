(ns sysrev.views.panels.user.billing
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.stripe :refer [pro-plans StripeCardInfo]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer
             [Segment Grid Row Column Button Icon Loader Header ListUI ListItem]]
            [sysrev.util :as util :refer [in? css parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:user :billing])

(defn DefaultSource [{:keys [default-source]}]
  [:div.bold
   [Icon {:name "credit card"}]
   (if (seq default-source)
     (let [{:keys [exp_month exp_year last4]} (:card default-source)]
       (str "Card expiring on " exp_month "/" (subs (str exp_year) 2 4)
            " and ending in " last4))
     "No payment method on file.")])

(defn PaymentSource
  "PaymentSource props are:
  {:default-source <RAtom of source>"
  [{:keys [default-source change-source-fn]}]
  (let [show-payment-form? (r/atom false)]
    (r/create-class
     {:render
      (fn [_]
        [Grid {:stackable true}
         [Row
          [Column {:width 2} "Payment"]
          (cond
            (nil? @default-source)
            [Column {:width 8} [Loader {:active true
                                        :inline "centered"}]]
            (not @show-payment-form?)
            [Column {:width 8} [DefaultSource {:default-source @default-source}]]
            @show-payment-form?
            [Column {:width 8}
             [StripeCardInfo {:add-payment-fn
                              (fn [payload]
                                (swap! show-payment-form? not)
                                (change-source-fn payload))}]])
          [Column {:width 6 :align "right"}
           [Button {:on-click #(swap! show-payment-form? not)}
            (cond @show-payment-form? [:div "Stop Editing Payment Information"]
                  (seq @default-source) [:div [Icon {:name "credit card"}] "Change payment method"]
                  (not (seq @default-source)) [:div [Icon {:name "credit card"}] "Add payment method"])]]]])})))

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan [{:keys [plans-url current-plan]}]
  (let [basic? (= (:nickname current-plan) "Basic")
        {:keys [nickname interval]} current-plan
        unlimited? (in? pro-plans nickname)
        mobile? (util/mobile?)]
    (if (nil? nickname)
      [Grid
       [Column {:width 2} "Plan"]
       [Column {:width 8} [Loader {:active true :inline "centered"}]]]
      [Grid (when mobile? {:vertical-align "middle"})
       [Column {:width 2} "Plan"]
       [Column {:width 8}
        (cond basic?      [:ul {:style {:padding-left "1.5em" :margin 0}}
                           [:li "Free Plan"]
                           [:li "Unlimited public projects"]]
              unlimited?  [:ul {:style {:padding-left "1.5em" :margin 0}}
                           [:li (str
                                 (if (re-matches #".*Org.*" nickname)
                                   "Team Pro "
                                   "Pro ")
                                 (if (= interval "month")
                                   "(paid monthly)"
                                   "(paid annually)"))]
                           [:li "Unlimited public and private projects"]])]
       [Column {:width 6 :align "right"}
        [Button {:class (css "nav-plans" [basic? "subscribe" unlimited? "unsubscribe"])
                 :color (when basic? "green")
                 :href plans-url}
         (cond basic?      (if mobile? "Upgrade" "Upgrade Your Plan")
               unlimited?  "Unsubscribe")]]])))

(defn UserBilling []
  (when-let [user-id @(subscribe [:self/user-id])]
    (dispatch [:data/load [:user/default-source user-id]])
    (dispatch [:data/load [:user/current-plan user-id]])
    [Segment
     [Header {:as "h4" :dividing true} "Billing"]
     [ListUI {:divided true :relaxed true}
      [ListItem [Plan {:plans-url "/user/plans"
                       :current-plan @(subscribe [:user/current-plan])}]]
      [ListItem
       [PaymentSource {:default-source (subscribe [:user/default-source user-id])
                       :change-source-fn (fn [payload] (dispatch [:action [:stripe/add-payment-user user-id payload]]))}]]]]))

(defmethod panel-content panel []
  (fn [_child] [UserBilling]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute user-billing "/user/:user-id/billing" [user-id]
             (let [user-id (parse-integer user-id)]
               (dispatch [:user-panel/set-user-id user-id])
               (when (= user-id @(subscribe [:self/user-id]))
                 (dispatch [:data/load [:user/default-source user-id]])
                 (dispatch [:data/load [:user/current-plan user-id]]))
               (dispatch [:set-active-panel panel])))
