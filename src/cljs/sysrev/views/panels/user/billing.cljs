(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET POST PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.semantic :refer [Grid Row Column Button ListUI Item Segment Header Icon Loader]]))

(def state (r/cursor app-db [:state :panels :user :billing]))

(reg-sub :billing/default-source (fn [db] @(r/cursor state [:default-source])))

(defn get-default-source
  [state]
  (let [default-source (r/cursor state [:default-source])
        default-source-error (r/cursor state [:default-source-error])
        user-id @(subscribe [:self/user-id])]
    (GET (str "/api/user/" user-id "/stripe/default-source")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                        (reset! default-source (-> response :result :default-source)))
          :error-handler (fn [error-response]
                           (reset! default-source-error (get-in error-response [:response :error :message])))})))

(defn DefaultSource
  []
  (let [default-source (r/cursor state [:default-source])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div {:style {:font-weight "bold"}}
         [Icon {:name "credit card"}]
         (if-not (empty? @default-source)
           (let [{:keys [brand exp_month exp_year last4]}
                 @default-source]
             (str brand " expiring on " exp_month "/" (-> exp_year
                                                          str
                                                          (subs 2 4)) " and ending in " last4))
           "No payment method on file.")])
      :get-initial-state
      (fn [this]
        (get-default-source state))})))

(defn PaymentSource
  []
  (let [default-source (r/cursor state [:default-source])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Grid {:stackable true}
         (if (nil? @default-source)
           [Row
            [Column {:width 2} "Payment"]
            [Column {:width 14} [Loader {:active true
                                         :inline "centered"}]]]
           [Row
            [Column {:width 2} "Payment"]
            [Column {:width 8} [DefaultSource]]
            [Column {:width 6 :align "right"}
             [Button {:on-click (fn [event]
                                  (dispatch [:payment/set-calling-route! (str "/user/settings/billing")])
                                  (dispatch [:navigate [:payment]]))}
              (if-not (empty? @default-source)
                [:div [Icon {:name "credit card"}] "Change payment method"]
                [:div [Icon {:name "credit card"}] "Add payment method"])]]])])
      :get-initial-state
      (fn [this]
        (get-default-source state))})))

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan
  []
  (let [current-plan (:name @(subscribe [:plans/current-plan]))]
    (dispatch [:fetch [:current-plan]])
    [Grid {:stackable true}
     (if (nil? current-plan)
       [Row
        [Column {:width 2} "Plan"]
        [Column {:width 14} [Loader {:active true
                                     :inline "centered"}]]]
       [Row
        [Column {:width 2} "Plan"]
        [Column {:width 8}
         (cond
           (= current-plan "Basic")
           "Free Plan, unlimited public projects"
           (= current-plan "Unlimited")
           "Unlimited Plan, unlimited public and private projects")]
        [Column {:width 6 :align "right"}
         [Button {:on-click (fn [event]
                              (nav-scroll-top "/user/plans"))
                  :color (if (= current-plan "Basic")
                           "green")}
          (cond (= current-plan "Basic")
                "Get private projects"
                (= current-plan "Unlimited")
                "Unsubscribe")]]])]))

(defn Billing
  []
  [Segment
   [Header {:as "h4"
            :dividing true}
    "Billing"]
   [ListUI {:divided true
            :relaxed true}
    [Item [Plan]]
    [Item [PaymentSource]]]])
