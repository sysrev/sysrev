(ns sysrev.views.panels.user.billing
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [re-frame.db :refer [app-db]]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.views.semantic :as s :refer
             [Segment Grid Row Column Button Icon]]))

(def panel [:user :billing])

(def state (r/cursor app-db [:state :panels panel]))

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
         [s/Icon {:name "credit card"}]
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
            [Column {:width 14} [s/Loader {:active true
                                           :inline "centered"}]]]
           [Row
            [Column {:width 2} "Payment"]
            [Column {:width 8} [DefaultSource]]
            [Column {:width 6 :align "right"}
             [Button {:on-click
                      ;; TODO: change to href with on-click
                      #(do (dispatch [:payment/set-calling-route! "/user/settings/billing"])
                           (dispatch [:navigate [:payment]]))}
              (if-not (empty? @default-source)
                [:div [Icon {:name "credit card"}] "Change payment method"]
                [:div [Icon {:name "credit card"}] "Add payment method"])]]])])
      :get-initial-state
      (fn [this]
        (get-default-source state))})))

;; TODO: shows Loader forever on actual null plan value (show error message?)
(defn Plan []
  (let [current-plan (:name @(subscribe [:plans/current-plan]))
        basic? (= current-plan "Basic")
        unlimited? (= current-plan "Unlimited")]
    (dispatch [:fetch [:current-plan]])
    [Grid {:stackable true}
     (if (nil? current-plan)
       [Row
        [Column {:width 2} "Plan"]
        [Column {:width 14} [s/Loader {:active true
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
                  :href "/user/plans"}
          (cond basic?      "Get private projects"
                unlimited?  "Unsubscribe")]]])]))

(defn Billing []
  [Segment
   [s/Header {:as "h4" :dividing true}
    "Billing"]
   [s/ListUI {:divided true :relaxed true}
    [s/ListItem [Plan]]
    [s/ListItem [PaymentSource]]]])
