(ns sysrev.views.panels.user.compensation
  (:require ["moment" :as moment]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.accounting :as accounting]
            [sysrev.data.core :refer [def-data load-data]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.views.semantic :as sui :refer
             [Grid Row Column Segment Header ListUI ListItem]]
            [sysrev.views.panels.project.support :refer [UserSupportSubscriptions]]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:user :compensation])

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

(defn- PaymentsOwed []
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
          [Column {:width 8} (str "Paid on: "
                                  (-> created
                                      (moment.)
                                      (.format "YYYY-MM-DD"))
                                  " at "
                                  (-> created
                                      (moment.)
                                      (.format "h:mm A")))]
          [Column {:width 3 :align "right"} (accounting/cents->string total-paid)]]]])

(defn- PaymentsPaid []
  (when-let [user-id @(subscribe [:self/user-id])]
    (with-loader [[:user/payments-paid user-id]] {}
      (when-let [payments (seq @(subscribe [:user/payments-paid]))]
        [Segment
         [Header {:as "h4" :dividing true} "Payments Paid"]
         [ListUI {:divided true :relaxed true}
          (doall (map (partial PaymentPaid) payments))]]))))

(defn- UserCompensation []
  [:div
   [PaymentsOwed]
   [PaymentsPaid]
   [UserSupportSubscriptions]])

(def-panel :uri "/user/:user-id/compensation" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (load-data :user/payments-owed user-id)
              (load-data :user/payments-paid user-id)
              (dispatch [:set-active-panel panel]))
  :content [UserCompensation]
  :require-login true)
