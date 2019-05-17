(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :refer [Segment Grid Row Column Button Icon Loader
                                           Header ListUI ListItem]]))

(def panel [:user :billing])

(def state (r/cursor app-db [:state :panels panel]))

(defn DefaultSource
  [{:keys [get-default-source default-source-atom]}]
  (r/create-class
   {:reagent-render
    (fn [this]
      [:div {:style {:font-weight "bold"}}
       [Icon {:name "credit card"}]
       (if-not (empty? @default-source-atom)
         (let [{:keys [brand exp_month exp_year last4]}
               @default-source-atom]
           (str brand " expiring on " exp_month "/" (-> exp_year
                                                        str
                                                        (subs 2 4)) " and ending in " last4))
         "No payment method on file.")])
    :component-did-mount
    (fn [this]
      (get-default-source))}))

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
    (fn [this]
      (get-default-source))}))

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan
  [{:keys [plans-route current-plan-atom fetch-current-plan]}]
  (let [current-plan (:name @current-plan-atom)
        basic? (= current-plan "Basic")
        unlimited? (= current-plan "Unlimited")]
    (fetch-current-plan)
    [Grid {:stackable true}
     (if (nil? current-plan)
       [Row
        [Column {:width 2} "Plan"]
        [Column {:width 14} [Loader {:active true
                                     :inline "centered"}]]]
       [Row
        [Column {:width 2} "Plan"]
        [Column {:width 8}
         (cond basic?      "Free Plan, unlimited public projects"
               unlimited?  "Unlimited Plan, unlimited public and private projects")]
        [Column {:width 6 :align "right"}
         [Button {:class (cond-> "nav-plans"
                           basic?     (str " subscribe")
                           unlimited? (str " unsubscribe"))
                  :color (when basic? "green")
                  :href plans-route}
          (cond basic?      "Get private projects"
                unlimited?  "Unsubscribe")]]])]))

(defn Billing []
  [Segment
   [Header {:as "h4" :dividing true}
    "Billing"]
   [ListUI {:divided true :relaxed true}
    [ListItem [Plan {:plans-route "/user/plans"
                     :current-plan-atom (subscribe [:plans/current-plan])
                     :fetch-current-plan (fn [] (dispatch [:fetch [:current-plan]]))}]]
    [ListItem [PaymentSource {:get-default-source stripe/get-user-default-source
                              :default-source-atom (subscribe [:stripe/default-source "user" @(subscribe [:self/user-id])])
                              :on-add-payment-method #(do (dispatch [:payment/set-calling-route! "/user/" @(subscribe [:self/user-id]) "/billing"])
                                                       (dispatch [:navigate [:payment]]))}]]]])
