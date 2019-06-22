(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :refer [Segment Grid Row Column Button Icon Loader
                                           Header ListUI ListItem]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [css]])
  (:require-macros [sysrev.macros :refer [setup-panel-state]]))

(setup-panel-state panel [:user :billing])

(defn DefaultSource
  [{:keys [get-default-source default-source-atom]}]
  (r/create-class
   {:reagent-render
    (fn [this]
      [:div.bold
       [Icon {:name "credit card"}]
       (if (seq @default-source-atom)
         (let [{:keys [brand exp_month exp_year last4]} @default-source-atom]
           (str brand " expiring on " exp_month "/" (subs (str exp_year) 2 4)
                " and ending in " last4))
         "No payment method on file.")])
    :component-did-mount
    (fn [this] (get-default-source))}))

(defn PaymentSource
  [{:keys [get-default-source default-source-atom on-add-payment-method]}]
  (r/create-class
   {:reagent-render
    (fn [this]
      [Grid {:stackable true}
       (if (nil? @default-source-atom)
         [Row
          [Column {:width 2} "Payment"]
          [Column {:width 14} [Loader {:active true
                                       :inline "centered"}]]]
         [Row
          [Column {:width 2} "Payment"]
          [Column {:width 8} [DefaultSource {:get-default-source get-default-source
                                             :default-source-atom default-source-atom}]]
          [Column {:width 6 :align "right"}
           [Button {:on-click on-add-payment-method}
            (if-not (empty? @default-source-atom)
              [:div [Icon {:name "credit card"}] "Change payment method"]
              [:div [Icon {:name "credit card"}] "Add payment method"])]]])])
    :component-did-mount
    (fn [this] (get-default-source))}))

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan
  [{:keys [plans-route current-plan-atom fetch-current-plan]}]
  (let [current-plan (:name @current-plan-atom)
        basic? (= current-plan "Basic")
        unlimited? (some #{"Unlimited_User" "Unlimited_Org"} [current-plan])
        mobile? (util/mobile?)]
    (fetch-current-plan)
    (if (util/mobile?)
      (if (nil? current-plan)
        [Grid
         [Column {:width 8} "Plan"]
         [Column {:width 8} [Loader {:active true :inline "centered"}]]]
        [Grid {:vertical-align "middle"}
         [Column {:width 10}
          (cond basic?      [:ul {:style {:padding-left "1.5em"}}
                             [:li "Free Plan"]
                             [:li "Unlimited public projects"]]
                unlimited?  [:ul {:style {:padding-left "1.5em"}}
                             [:li "Pro Plan"]
                             [:li "Unlimited public and private projects"]])]
         [Column {:width 6 :align "right"}
          [Button {:class (css "nav-plans" [basic? "subscribe" unlimited? "unsubscribe"])
                   :color (when basic? "green")
                   :href plans-route}
           (cond basic?      "Upgrade"
                 unlimited?  "Unsubscribe")]]])
      (if (nil? current-plan)
        [Grid
         [Column {:width 2} "Plan"]
         [Column {:width 12} [Loader {:active true :inline "centered"}]]]
        [Grid
         [Column {:width 2} "Plan"]
         [Column {:width 8}
          (cond basic?      [:ul {:style {:padding-left "1.5em" :margin 0}}
                             [:li "Free Plan"]
                             [:li "Unlimited public projects"]]
                unlimited?  [:ul {:style {:padding-left "1.5em"}}
                             [:li "Pro Plan"]
                             [:li "Unlimited public and private projects"]])]
         [Column {:width 6 :align "right"}
          [Button {:class (css "nav-plans" [basic? "subscribe" unlimited? "unsubscribe"])
                   :color (when basic? "green")
                   :href plans-route}
           (cond basic?      "Get private projects"
                 unlimited?  "Unsubscribe")]]]))))

(defn Billing []
  (let [self-id @(subscribe [:self/user-id])
        billing-url (str "/user/" self-id "/billing")]
    (dispatch [:user/set-on-subscribe-nav-to-url! billing-url])
    [Segment
     [Header {:as "h4" :dividing true} "Billing"]
     [ListUI {:divided true :relaxed true}
      [ListItem [Plan {:plans-route "/user/plans"
                       :current-plan-atom (subscribe [:user/current-plan])
                       :fetch-current-plan #(dispatch [:fetch [:user/current-plan self-id]])}]]
      [ListItem [PaymentSource
                 {:get-default-source stripe/get-user-default-source
                  :default-source-atom (subscribe [:stripe/default-source "user" self-id])
                  :on-add-payment-method #(do (dispatch [:payment/set-calling-route! billing-url])
                                              (dispatch [:navigate [:payment]]))}]]]]))
