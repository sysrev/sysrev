(ns sysrev.views.panels.user.billing
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.action.core :refer [run-action]]
            [sysrev.data.core :refer [load-data]]
            [sysrev.nav :as nav]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :refer
             [Segment Grid Column Button Icon Loader Header ListUI ListItem]]
            [sysrev.util :as util :refer [css parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:user :billing])

(defn DefaultSource [default-source]
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
    (fn [{:keys [default-source change-source-fn]}]
      [Grid {:stackable true}
       [Column {:width 2} "Payment"]
       [Column {:width 8 :style {:min-height "4em"}}
        (if (nil? @default-source)
          [Loader {:active true, :inline "centered", :size "small"}]
          (if @show-payment-form?
            [stripe/StripeCardInfo {:add-payment-fn (fn [payload]
                                                      (swap! show-payment-form? not)
                                                      (change-source-fn payload))}]
            [DefaultSource @default-source]))]
       [Column {:width 6 :align "right"}
        ;; wait to render button until plan status is loaded
        (when (some? @default-source)
          [:button.ui.icon.right.labeled.button
           {:on-click #(swap! show-payment-form? not)}
           [:div.ui.icon.label
            [Icon {:name (if @show-payment-form? "times" "credit card")}]]
           (cond @show-payment-form?    "Cancel"
                 (seq @default-source)  "Change payment method"
                 :else                  "Add payment method")])]])))

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan [{:keys [plans-url current-plan]}]
  (let [basic? (= (:nickname current-plan) "Basic")
        {:keys [nickname interval]} current-plan
        unlimited? (stripe/pro? nickname)
        mobile? (util/mobile?)]
    (if (nil? nickname)
      [Grid {:stackable true}
       [Column {:width 2} "Plan"]
       [Column {:width 8} [Loader {:active true :inline "centered"}]]]
      [Grid (cond-> {:stackable true}
              mobile? (merge {:class "middle aligned"
                              :vertical-align "middle"}))
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
        (when unlimited?
          [Button {:href (nav/make-url plans-url
                                       {:changing-interval true})} "Change Billing Interval"])
        [Button {:class (css "nav-plans" [basic? "subscribe" unlimited? "unsubscribe"])
                 :color (when basic? "green")
                 :href plans-url}
         (cond basic?      (if mobile? "Upgrade" "Upgrade Your Plan")
               unlimited?  "Unsubscribe")]]])))

(defn UserBilling []
  (when-let [user-id @(subscribe [:self/user-id])]
    (load-data :user/default-source user-id)
    (load-data :user/current-plan user-id)
    [Segment
     [Header {:as "h4" :dividing true, :style {:margin-bottom "0.6rem"}} "Billing"]
     [ListUI {:divided true, :relaxed true, :style {:margin-top "0"}}
      [ListItem [:div {:style {:padding "0.6rem 0"}}
                 [Plan {:plans-url "/user/plans"
                        :current-plan @(subscribe [:user/current-plan])}]]]
      [ListItem [:div {:style {:padding "0.6rem 0"}}
                 [PaymentSource
                  {:default-source (subscribe [:user/default-source user-id])
                   :change-source-fn #(run-action :stripe/add-payment-user user-id %)}]]]]]))

(def-panel :uri "/user/:user-id/billing" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (when (= user-id @(subscribe [:self/user-id]))
                (load-data :user/default-source user-id)
                (load-data :user/current-plan user-id))
              (dispatch [:set-active-panel panel]))
  :content (when-let [user-id @(subscribe [:self/user-id])]
             (with-loader [[:user/default-source user-id]
                           [:user/current-plan user-id]] {}
               [UserBilling]))
  :require-login true)
