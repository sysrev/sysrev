(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer
             [Segment Grid Row Column Button Icon Loader Header ListUI ListItem]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? css parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute with-loader]]))

(setup-panel-state panel [:user :billing])

(defn DefaultSource [{:keys [default-source]}]
  [:div.bold
   [Icon {:name "credit card"}]
   (if (seq default-source)
     (let [{:keys [brand exp_month exp_year last4]} default-source]
       (str brand " expiring on " exp_month "/" (subs (str exp_year) 2 4)
            " and ending in " last4))
     "No payment method on file.")])

(defn PaymentSource [{:keys [default-source on-add-payment-method]}]
  [Grid {:stackable true}
   (if (nil? default-source)
     [Row
      [Column {:width 2} "Payment"]
      [Column {:width 14} [Loader {:active true
                                   :inline "centered"}]]]
     [Row
      [Column {:width 2} "Payment"]
      [Column {:width 8} [DefaultSource {:default-source default-source}]]
      [Column {:width 6 :align "right"}
       [Button {:on-click on-add-payment-method}
        (if (seq default-source)
          [:div [Icon {:name "credit card"}] "Change payment method"]
          [:div [Icon {:name "credit card"}] "Add payment method"])]]])])

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan [{:keys [plans-url current-plan]}]
  (let [basic? (= (:name current-plan) "Basic")
        unlimited? (in? #{"Unlimited_User" "Unlimited_Org"} (:name current-plan))
        mobile? (util/mobile?)]
    #_ (js/console.log "Plan: current-plan = " (:name current-plan))
    (if mobile?
      (if (nil? (:name current-plan))
        [Grid
         [Column {:width 8} "Plan"]
         [Column {:width 8} [Loader {:active true :inline "centered"}]]]
        [Grid {:vertical-align "middle"}
         [Column {:width 10}
          (cond basic?      [:ul {:style {:padding-left "1.5em" :margin 0}}
                             [:li "Free Plan"]
                             [:li "Unlimited public projects"]]
                unlimited?  [:ul {:style {:padding-left "1.5em" :margin 0}}
                             [:li "Pro Plan"]
                             [:li "Unlimited public and private projects"]])]
         [Column {:width 6 :align "right"}
          [Button {:class (css "nav-plans" [basic? "subscribe" unlimited? "unsubscribe"])
                   :color (when basic? "green")
                   :href plans-url}
           (cond basic?      "Upgrade"
                 unlimited?  "Unsubscribe")]]])
      (if (nil? (:name current-plan))
        [Grid
         [Column {:width 2} "Plan"]
         [Column {:width 12} [Loader {:active true :inline "centered"}]]]
        [Grid
         [Column {:width 2} "Plan"]
         [Column {:width 8}
          (cond basic?      [:ul {:style {:padding-left "1.5em" :margin 0}}
                             [:li "Free Plan"]
                             [:li "Unlimited public projects"]]
                unlimited?  [:ul {:style {:padding-left "1.5em" :margin 0}}
                             [:li "Pro Plan"]
                             [:li "Unlimited public and private projects"]])]
         [Column {:width 6 :align "right"}
          [Button {:class (css "nav-plans" [basic? "subscribe" unlimited? "unsubscribe"])
                   :color (when basic? "green")
                   :href plans-url}
           (cond basic?      "Get private projects"
                 unlimited?  "Unsubscribe")]]]))))

(defn UserBilling []
  (when-let [self-id @(subscribe [:self/user-id])]
    (let [billing-url (str "/user/" self-id "/billing")]
      (dispatch [:user/set-on-subscribe-nav-to-url! billing-url])
      (dispatch [:data/load [:user/default-source self-id]])
      (dispatch [:data/load [:user/current-plan self-id]])
      #_ (js/console.log "UserBilling: current-plan = " (:name @(subscribe [:user/current-plan])))
      #_ (js/console.log "UserBilling: default-source = " (str @(subscribe [:user/default-source])))
      [Segment
       [Header {:as "h4" :dividing true} "Billing"]
       [ListUI {:divided true :relaxed true}
        [ListItem [Plan {:plans-url "/user/plans"
                         :current-plan @(subscribe [:user/current-plan])}]]
        [ListItem
         [PaymentSource
          {:default-source @(subscribe [:user/default-source self-id])
           :on-add-payment-method #(do (dispatch [:data/load [:user/default-source self-id]])
                                       (dispatch [:stripe/set-calling-route! billing-url])
                                       (dispatch [:navigate [:payment]]))}]]]])))

(defmethod panel-content panel []
  (fn [child] [UserBilling]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute user-billing "/user/:user-id/billing" [user-id]
             (let [user-id (parse-integer user-id)]
               (dispatch [:user-panel/set-user-id user-id])
               (when (= user-id @(subscribe [:self/user-id]))
                 (dispatch [:data/load [:user/default-source user-id]])
                 (dispatch [:data/load [:user/current-plan user-id]]))
               (dispatch [:set-active-panel panel])))
