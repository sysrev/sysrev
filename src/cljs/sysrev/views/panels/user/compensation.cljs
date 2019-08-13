(ns sysrev.views.panels.user.compensation
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.accounting :as accounting]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :as sui :refer
             [Grid Row Column Segment Header ListUI ListItem]]
            [sysrev.views.panels.project.support :refer [UserSupportSubscriptions]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute with-loader]]))

(setup-panel-state panel [:user :compensation] {:state-var state
                                                :get-fn panel-get :set-fn panel-set
                                                :get-sub ::get :set-event ::set})

(def-data :user/payments-owed
  :uri (fn [user-id] (str "/api/user/" user-id "/payments-owed"))
  :loaded? (fn [db user-id] (-> (get-in db [:data :user-payments user-id])
                                (contains? :owed)))
  :process (fn [{:keys [db]} [user-id] {:keys [payments-owed]}]
             {:db (assoc-in db [:data :user-payments user-id :owed] payments-owed)}))

(reg-sub :user/payments-owed
         (fn [db [_ & [user-id]]]
           (let [user-id (or user-id (current-user-id db))]
             (get-in db [:data :user-payments user-id :owed]))))

(def-data :user/payments-paid
  :uri (fn [user-id] (str "/api/user/" user-id "/payments-paid"))
  :loaded? (fn [db user-id] (-> (get-in db [:data :user-payments user-id])
                                (contains? :paid)))
  :process (fn [{:keys [db]} [user-id] {:keys [payments-paid]}]
             {:db (assoc-in db [:data :user-payments user-id :paid] payments-paid)}))

(reg-sub :user/payments-paid
         (fn [db [_ & [user-id]]]
           (let [user-id (or user-id (current-user-id db))]
             (get-in db [:data :user-payments user-id :paid]))))

(defn- PaymentOwed
  "Display a payment-owed map"
  [{:keys [total-owed project-id project-name]}]
  ^{:key (gensym project-id)}
  [ListItem
   [Grid [Row
          [Column {:width 5} project-name]
          [Column {:width 8}]
          [Column {:width 3 :align "right"} (accounting/cents->string total-owed)]]]])

(defn PaymentsOwed []
  (when-let [user-id @(subscribe [:self/user-id])]
    [Segment
     [Header {:as "h4" :dividing true} "Payments Owed"]
     (with-loader [[:user/payments-owed user-id]] {}
       (if-let [payments (seq @(subscribe [:user/payments-owed]))]
         [ListUI {:divided true :relaxed true}
          (doall (map (partial PaymentOwed) payments))]
         [:div>h4 "You do not currently have any payments owed"]))]))

(defn- PaymentPaid
  "Display a payment-owed map"
  [{:keys [total-paid project-id project-name created]}]
  ^{:key (gensym project-id)}
  [ListItem
   [Grid [Row
          [Column {:width 5} project-name]
          [Column {:width 8} (str "Paid on: " (util/unix-epoch->date-string created))]
          [Column {:width 3 :align "right"} (accounting/cents->string total-paid)]]]])

(defn PaymentsPaid []
  (when-let [user-id @(subscribe [:self/user-id])]
    (with-loader [[:user/payments-paid user-id]] {}
      (when-let [payments (seq @(subscribe [:user/payments-paid]))]
        [Segment
         [Header {:as "h4" :dividing true} "Payments Paid"]
         [ListUI {:divided true :relaxed true}
          (doall (map (partial PaymentPaid) payments))]]))))

(defn UserCompensation []
  [:div
   [PaymentsOwed]
   [PaymentsPaid]
   [UserSupportSubscriptions]])

(defmethod panel-content panel []
  (fn [child] [UserCompensation]))

(defmethod logged-out-content panel []
  (logged-out-content :logged-out))

(sr-defroute user-compensation "/user/:user-id/compensation" [user-id]
             (let [user-id (parse-integer user-id)]
               (dispatch [:user-panel/set-user-id user-id])
               (dispatch [:data/load [:user/payments-owed user-id]])
               (dispatch [:data/load [:user/payments-paid user-id]])
               (dispatch [:set-active-panel panel])))
