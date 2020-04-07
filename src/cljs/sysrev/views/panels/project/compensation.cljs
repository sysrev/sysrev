(ns sysrev.views.panels.project.compensation
  (:require [ajax.core :refer [POST GET PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx trim-v dispatch]]
            [sysrev.accounting :as acct]
            [sysrev.charts.chartjs :as chartjs]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.charts :as charts]
            [sysrev.paypal :as paypal]
            [sysrev.views.semantic :as s :refer [Button Dropdown]]
            [sysrev.shared.charts :refer [paul-tol-colors]]
            [sysrev.util :as util :refer [index-by ensure-pred]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :compensation] {:state-var state})

(def admin-fee 0.20)

(def check-pending-interval 600000)

(defn amount->cents [amount-string]
  (some->> (not-empty amount-string)
           (acct/string->cents)
           (ensure-pred integer?)))

(defn rate->string [rate]
  (str (acct/cents->string (:amount rate)) " / " (:item rate)))

(defn admin-fee-text [amount admin-fee]
  (str (acct/cents->string (* amount admin-fee)) " admin fee"))

(defn AdminFee [amount admin-fee]
  [:span.medium-weight.orange-text {:style {:font-size "0.9rem"}}
   (str "(+" (admin-fee-text amount admin-fee) ")")])

(defn CompensationAmount
  "Show the compensation text related to a compensation"
  [compensation admin-fee]
  (let [{:keys [rate]} compensation
        {:keys [amount item]} rate]
    [:span {:style {:font-size "1.2rem"}}
     (acct/cents->string amount) " " [AdminFee amount admin-fee]
     " / " (str item)]))

(defn get-compensations! [state]
  (let [project-id @(subscribe [:active-project-id])
        loading? (r/cursor state [:loading :project-compensations])
        project-compensations (r/cursor state [:project-compensations])]
    (reset! loading? true)
    (GET "/api/project-compensations"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (reset! loading? false)
                     (reset! project-compensations
                             (index-by :compensation-id (:compensations result))))
          :error-handler (fn [_response]
                           (reset! loading? false)
                           (util/log-err "get-compensations! - project-id = %s" project-id))})))

(defn get-project-users-current-compensation! [state]
  (let [project-id @(subscribe [:active-project-id])
        loading? (r/cursor state [:loading :project-users-current-compensation])
        users-current-comp (r/cursor state [:project-users-current-compensation])]
    (reset! loading? true)
    (GET
     "/api/project-users-current-compensation"
     {:params {:project-id project-id}
      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
      :handler (fn [{:keys [result]}]
                 (reset! loading? false)
                 (reset! users-current-comp
                         (->> (:project-users-current-compensation result)
                              (map #(update % :compensation-id (fn [x] (or x "none"))))
                              (index-by :user-id))))
      :error-handler (fn [_response]
                       (reset! loading? false)
                       (util/log-err "project-users-current-compensation - project-id = %s"
                                     project-id))})))

(defn compensation-owed! [state]
  (let [project-id @(subscribe [:active-project-id])
        loading? (r/cursor state [:loading :compensation-owed])
        compensation-owed (r/cursor state [:compensation-owed])]
    (reset! loading? true)
    (GET "/api/compensation-owed"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! loading? false)
                     (reset! compensation-owed
                             (get-in response [:result :compensation-owed])))
          :error-handler (fn [_response]
                           (reset! loading? false)
                           (util/log-err "compensation-owed - project-id = %s" project-id))})))

(defn pay-user!
  "Pay the user the amount owed to them"
  [state user-id compensation admin-fee]
  (let [project-id @(subscribe [:active-project-id])
        loading? (r/cursor state [:retrieving-pay? user-id])
        pay-error (r/cursor state [:pay-error user-id])
        confirming? (r/cursor state [:confirming? user-id])]
    (reset! loading? true)
    (reset! pay-error nil)
    (POST "/api/pay-user"
          {:params {:project-id project-id
                    :user-id user-id
                    :compensation compensation
                    :admin-fee admin-fee}
           :headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :handler (fn [_response]
                      (reset! loading? false)
                      (compensation-owed! state)
                      (reset! confirming? false)
                      (dispatch [:project/get-funds]))
           :error-handler (fn [error-response]
                            (reset! loading? false)
                            (compensation-owed! state)
                            (reset! pay-error (get-in error-response
                                                      [:response :error :message])))})))

(defn get-default-compensation!
  "Load the current default compensation from server"
  [state]
  (let [project-id @(subscribe [:active-project-id])
        loading? (r/cursor state [:loading :project-compensations])
        default-compensation (r/cursor state [:default-project-compensation])]
    (reset! loading? true)
    (GET "/api/get-default-compensation"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (let [{:keys [compensation-id]} result]
                       (reset! loading? false)
                       (reset! default-compensation (or compensation-id "none"))))
          :error-handler (fn [_response]
                           (reset! loading? false)
                           (util/log-err "get-default-compensation - project-id = %s"
                                         project-id))})))

(reg-event-fx :project/get-funds [trim-v]
              (fn [_cofx _event]
                (let [project-id @(subscribe [:active-project-id])
                      project-funds (r/cursor state [:project-funds])]
                  (GET "/api/project-funds"
                       {:params {:project-id project-id}
                        :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                        :handler #(reset! project-funds (get-in % [:result :project-funds]))
                        :error-handler #(util/log-err "get-project-funds - project-id = %s"
                                                      project-id)}))
                {}))

(defn check-pending-transactions []
  (PUT "/api/check-pending-transaction"
       {:params {:project-id @(subscribe [:active-project-id])}
        :headers {"x-csrf-token" @(subscribe [:csrf-token])}
        :handler #(dispatch [:project/get-funds])
        :error-handler #(js/console.error "[[check-pending-transaction]]: error " %)}))

(defn ProjectFunds []
  (let [{:keys [project-funds]} @state
        {:keys [current-balance compensation-outstanding available-funds
                admin-fees pending-funds]} project-funds]
    [:div
     [:h4.ui.dividing.header "Project Funds"]
     [:div.ui.two.column.middle.aligned.vertically.divided.grid
      [:div.row
       [:div.column "Available Funds:"]
       [:div.right.aligned.column (acct/cents->string available-funds)]]
      (when (> pending-funds 0)
        [:div.row.orange-text
         [:div.column "Pending Deposits:"]
         [:div.right.aligned.column (acct/cents->string pending-funds)]])
      [:div.row
       [:div.column "Outstanding Owed:"]
       [:div.right.aligned.column  (acct/cents->string compensation-outstanding)]]
      [:div.row
       [:div.column "Outstanding Fees:"]
       [:div.right.aligned.column (acct/cents->string admin-fees)]]
      [:div.row
       [:div.column "Current Balance:"]
       [:div.right.aligned.column (acct/cents->string current-balance)]]]]))

(defn ToggleCompensationEnabled [{:keys [compensation-id] :as _compensation}]
  (let [project-id @(subscribe [:active-project-id])
        compensation-atom (r/cursor state [:project-compensations compensation-id])
        enabled (r/cursor compensation-atom [:enabled])
        updating? (r/cursor compensation-atom [:updating?])]
    [Button {:size "small" :toggle true :active @enabled :disabled @updating?
             :style {:min-width "9rem"}
             :on-click (fn [_]
                         (swap! enabled not)
                         (reset! updating? true)
                         (PUT "/api/toggle-compensation-enabled"
                              {:params {:project-id project-id
                                        :compensation-id compensation-id
                                        :enabled @enabled}
                               :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                               :handler (fn [_response]
                                          (reset! updating? false)
                                          (get-compensations! state))
                               :error-handler (fn [_response]
                                                (util/log-err "update-compensation!")
                                                (reset! updating? false))}))}
     (if @enabled "Active" "Disabled")]))

(defn CreateCompensationForm []
  (let [project-id @(subscribe [:active-project-id])
        compensation-amount (r/cursor state [:compensation-amount])
        creating? (r/cursor state [:creating-new-compensation?])
        loading? (r/cursor state [:loading :project-compensations])
        error-message (r/cursor state [:create-compensation-error])
        create-compensation!
        (fn [cents]
          (reset! creating? true)
          (reset! error-message nil)
          (POST "/api/project-compensation"
                {:params {:project-id project-id
                          :rate {:item "article" :amount cents}}
                 :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                 :handler (fn [_response]
                            (reset! creating? false)
                            (get-compensations! state)
                            (reset! compensation-amount nil))
                 :error-handler (fn [error]
                                  (reset! error-message (get-in error [:response :error :message]))
                                  (reset! creating? false))}))]
    [:form.ui.stackable.form.add-rate
     {:on-submit (util/wrap-prevent-default
                  #(let [amount @compensation-amount
                         cents (amount->cents amount)
                         valid? (re-matches acct/valid-usd-regex amount)]
                     (cond (empty? amount)  nil
                           (not valid?)     (reset! error-message "Amount is not valid")
                           (= cents 0)      (reset! compensation-amount "0.00")
                           :else            (create-compensation! cents))))}
     [:div.stackable.fields
      [:div.eleven.wide.field
       [:div.ui.labeled.input {:style {:width "9rem"}}
        [:div.ui.label "$"]
        [:input {:type "text"
                 :id "create-compensation-amount"
                 :aria-label "compensation-amount"
                 :placeholder "Amount"
                 :autoComplete "off"
                 :value (or @compensation-amount "")
                 :on-change (util/on-event-value
                             #(do (reset! compensation-amount %)
                                  (if (and (not-empty %)
                                           (not (re-matches acct/valid-usd-regex %)))
                                    (reset! error-message "Amount is not valid")
                                    (reset! error-message nil))))}]]
       (when-let [cents-amount (->> (amount->cents @compensation-amount)
                                    (ensure-pred pos?))]
         [:span {:style {:margin-left "0.5em" :font-size "1.2rem"}}
          [AdminFee cents-amount admin-fee] " / article"])]
      [:div.five.wide.field {:style {:text-align "right"}}
       [Button {:type "submit" :color "blue" :style {:min-width "9rem"}
                :disabled (or @creating? @loading?)}
        "Create Rate"]]]
     (when @error-message
       [s/Message {:negative true} @error-message])]))

(defn ProjectRates []
  (let [project-compensations (->> (vals (:project-compensations @state))
                                   (sort-by #(get-in % [:rate :amount])))]
    [:div#project-rates
     [:h4.ui.dividing.header "Project Rates"]
     (when (seq project-compensations)
       [:div.ui.relaxed.divided.list
        (doall
         (for [c project-compensations]
           [:div.item {:key (:compensation-id c)}
            [:div.ui.middle.aligned.grid.project-rates
             [:div.eleven.wide.column [CompensationAmount c admin-fee]]
             [:div.five.wide.right.aligned.column [ToggleCompensationEnabled c]]]]))])
     (when (seq project-compensations)
       [:div.ui.divider])
     [CreateCompensationForm]]))

(defn CompensationGraph
  "Labels is a list of names, amount-owed is a vector of amounts owed."
  [labels amount-owed]
  (let [font-color (charts/graph-text-color)
        data {:labels labels
              :datasets [{:data amount-owed
                          :backgroundColor (nth paul-tol-colors (count labels))}]}
        options {:scales
                 {:yAxes
                  [{:display true
                    :scaleLabel {:fontColor font-color
                                 :display false
                                 :padding {:top 200
                                           :bottom 200}}
                    :stacked false
                    :ticks {:fontColor font-color
                            :suggestedMin 0
                            :callback (fn [value index values]
                                        (if (or (= index 0)
                                                (= (/ (apply max values) 2)
                                                   value)
                                                (= (apply max values)
                                                   value)
                                                (= value 0))
                                          (acct/cents->string value)
                                          ""))}}]
                  :xAxes
                  [{:maxBarThickness 10
                    :scaleLabel {:fontColor font-color}
                    :ticks {:fontColor font-color}
                    :gridLines {:color (charts/graph-border-color)}}]}
                 :legend {:display false}
                 :tooltips {:callbacks {:label #(acct/cents->string (.-yLabel %))}}}]
    [chartjs/bar
     {:data data
      :height (+ 50 (* 12 (count labels)))
      :width (* (count labels) 200)
      :options options}]))

(defn CompensationSummary []
  (when-let [entries (seq (->> (:compensation-owed @state)
                               (filter #(or (some-> (:compensation-owed %) pos?)
                                            (:last-payment %)))))]
    [:div#reviewer-amounts.ui.segment
     [:h4.ui.dividing.header "Amounts Earned"]
     [:div.ui.relaxed.divided.list
      (doall
       (for [{:keys [compensation-owed last-payment #_ connected
                     user-id admin-fee #_ username]} entries]
         (let [retrieving-amount-owed? @(r/cursor state [:loading :compensation-owed])
               confirming? (r/cursor state [:confirming? user-id])
               retrieving-pay? @(r/cursor state [:retrieving-pay? user-id])
               error-message (r/cursor state [:pay-error user-id])
               username @(subscribe [:user/display user-id])]
           [:div.item {:key username :data-username username}
            [:div.ui.grid
             [:div.five.wide.column [:i.user.icon] username]
             [:div.two.wide.column (acct/cents->string compensation-owed)]
             [:div.four.wide.column
              (when last-payment
                (str "Last Payment: " (util/unix-epoch->date-string last-payment)))]
             [:div.five.wide.right.aligned.column
              (cond (and (> compensation-owed 0) (not @confirming?))
                    [Button {:size "small" :color "blue" :class "pay-user"
                             :on-click #(reset! confirming? true)
                             :disabled (or retrieving-pay? retrieving-amount-owed?)}
                     "Pay"])]]
            (when @confirming?
              [:div.ui.message {:position "absolute"}
               [:div.ui.two.column.grid
                [:div.row
                 [:div.column "Compensation:"]
                 [:div.right.aligned.column (acct/cents->string compensation-owed)]]
                [:div.row
                 [:div.column "Admin Fees:"]
                 [:div.right.aligned.column (acct/cents->string admin-fee)]]
                [:div.row
                 [:div.column "Total to be Deducted:"]
                 [:div.right.aligned.column.bold
                  (acct/cents->string (+ compensation-owed admin-fee))]]
                [:div.row
                 [:div.column]
                 [:div.right.aligned.column
                  [:div.ui.buttons
                   [Button {:size "small" :color "blue" :class "confirm-pay-user"
                            :on-click #(pay-user! state user-id compensation-owed admin-fee)
                            :disabled (or retrieving-pay? retrieving-amount-owed?)}
                    "Confirm"]
                   [Button {:size "small"
                            :on-click #(do (reset! confirming? false)
                                           (reset! error-message nil))}
                    "Cancel"]]]]]
               (when @error-message [:div.ui.error.message @error-message])])])))]]))

(defn compensation-options
  [project-compensations]
  (conj (->> (vals project-compensations)
             (sort-by #(get-in % [:rate :amount]))
             (filter :enabled)
             (map (fn [compensation]
                    {:text (rate->string (:rate compensation))
                     :value (:compensation-id compensation)})))
        {:text "None" :value "none"}))

(defn UserCompensationDropdown [user-id]
  (let [project-id @(subscribe [:active-project-id])
        project-compensations (r/cursor state [:project-compensations])
        user-atom (r/cursor state [:project-users-current-compensation user-id])
        compensation-id (r/cursor user-atom [:compensation-id])
        updating? (r/cursor user-atom [:updating?])
        loading? (r/cursor state [:loading :project-users-current-compensation])]
    [Dropdown {:style {:min-width "12rem" :font-size "0.9em"}
               :options (compensation-options @project-compensations)
               :selection true
               :disabled (or @updating? @loading?)
               :loading @updating?
               :value @compensation-id
               :on-change (fn [_e ^js data]
                            (let [value (.-value data)]
                              (when-not (= value @compensation-id)
                                (reset! compensation-id value)
                                (reset! updating? true)
                                (PUT "/api/set-user-compensation"
                                     {:params {:project-id project-id
                                               :compensation-id value
                                               :user-id user-id}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [_response]
                                                 (reset! updating? false)
                                                 (get-project-users-current-compensation! state))
                                      :error-handler (fn [_response]
                                                       (reset! updating? false))}))))}]))

(defn DefaultCompensationDropdown []
  (let [project-id @(subscribe [:active-project-id])
        project-compensations (r/cursor state [:project-compensations])
        default-compensation (r/cursor state [:default-project-compensation])
        updating? (r/cursor state [:updating :project-compensations])
        loading? (r/cursor state [:loading :project-compensations])]
    [Dropdown {:style {:min-width "12rem" :font-size "0.9em"}
               :options (compensation-options @project-compensations)
               :selection true
               :loading @updating?
               :disabled (or @updating? @loading?)
               :value @default-compensation
               :on-change (fn [_e ^js data]
                            (let [value (.-value data)]
                              (when-not (= value @default-compensation)
                                (reset! default-compensation value)
                                (reset! updating? true)
                                (PUT "/api/set-default-compensation"
                                     {:params {:project-id project-id
                                               :compensation-id (when (not= value "none") value)}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [_response]
                                                 (get-default-compensation! state)
                                                 (reset! updating? false))
                                      :error-handler (fn [_response]
                                                       (reset! updating? false))}))))}]))

(defn UserRateEntry [name-content control-content]
  [:div.item>div.ui.stackable.middle.aligned.grid.user-compensation-entry
   [:div.ten.wide.left.aligned.column name-content]
   [:div.six.wide.right.aligned.column control-content]])

(defn UserRates []
  (let [project-compensations (r/cursor state [:project-compensations])
        current-compensation (r/cursor state [:project-users-current-compensation])
        member-ids @(subscribe [:project/member-user-ids nil true])
        entries (vals @current-compensation)]
    (when (and @project-compensations @current-compensation)
      [:div#user-rates
       [:h4.ui.dividing.header "Rates by Reviewer"]
       [:div.ui.relaxed.divided.list
        ^{:key :default}
        [UserRateEntry
         [:span "New User Default"]
         [DefaultCompensationDropdown]]
        (doall (for [user-id member-ids]
                 (when (first (->> entries (filter #(= (:user-id %) user-id))))
                   ^{:key user-id}
                   [UserRateEntry
                    [:span [:i.user.icon] @(subscribe [:user/display user-id])]
                    [UserCompensationDropdown user-id]])))]])))

(defn ProjectCompensationPanel []
  (r/create-class
   {:reagent-render
    (fn []
      [:div.project-content.compensation
       [:div.ui.segment.funds
        [:div.ui.stackable.divided.grid
         [:div.eleven.wide.column.project-funds
          [ProjectFunds]]
         [:div.five.wide.column.add-funds
          [paypal/AddFunds :on-success #(js/setTimeout check-pending-transactions 1000)]]
         #_ [:div.column [support/SupportFormOnce support/state]]]]
       [:div.ui.segment.rates
        [:div.ui.stackable.divided.two.column.grid
         [:div.column.project-rates [ProjectRates]]
         [:div.column.user-rates [UserRates]]]]
       [:div.ui.one.column.stackable.grid
        [:div.column [CompensationSummary]]]])
    :component-will-mount
    (fn [_this]
      (reset! (r/cursor state [:project-funds]) nil)
      (dispatch [:project/get-funds])
      (check-pending-transactions)
      (get-compensations! state)
      (get-default-compensation! state)
      (get-project-users-current-compensation! state)
      (compensation-owed! state)
      (reset! (r/cursor state [:compensation-amount]) nil)
      (reset! (r/cursor state [:create-compensation-error]) nil)
      ;; TODO: run a timer to update pending status
      #_ (let [pending-funds (r/cursor state [:project-funds :pending-funds])]
           (util/continuous-update-until check-pending-transactions
                                         #(= @pending-funds 0)
                                         (constantly nil)
                                         check-pending-interval)))}))

(defmethod panel-content [:project :project :compensations] []
  (fn [_child]
    ;; TODO: hide and show message if user is not project admin
    [ProjectCompensationPanel]))
