(ns sysrev.views.panels.project.compensation
  (:require [ajax.core :refer [POST GET DELETE PUT]]
            [cljsjs.moment]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx trim-v dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as acct]
            [sysrev.charts.chartjs :as chartjs]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.charts :as charts]
            [sysrev.paypal :as paypal]
            [sysrev.views.semantic :as s :refer [Button Dropdown]]
            [sysrev.views.panels.project.support :as support]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? ->map-with-key]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:project :project :compensation])

(def state (r/cursor app-db [:state :panels panel]))

(def admin-fee 0.20)

(def check-pending-interval 600000)

(defn admin-fee-text
  "amount is an integer amount in cents"
  [amount admin-fee]
  (str (acct/cents->string (* amount admin-fee)) " admin fee"))

(defn AdminFee
  [amount admin-fee]
  [:span {:style {:font-size "0.8em"
                  :color "red"
                  :font-weight "bold"}}
   (str "(+" (acct/cents->string (* amount admin-fee)) " admin fee)")])

(defn CompensationAmount
  "Show the compensation text related to a compensation"
  [compensation admin-fee]
  (let [amount (get-in compensation [:rate :amount])
        item (get-in compensation [:rate :item])
        style {:style {:font-size "1.3em"}}]
    [:div
     [:span style (acct/cents->string amount)]
     " " [AdminFee amount admin-fee]
     [:span style (str " / " item)]]))

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
                             (->map-with-key :compensation-id (:compensations result))))
          :error-handler (fn [response]
                           (reset! loading? false)
                           ($ js/console log "[Error] retrieving for project-id: " project-id))})))

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
                              (->map-with-key :user-id))))
      :error-handler (fn [response]
                       (reset! loading? false)
                       ($ js/console log
                          "[Error] retrieving project-users-current-compensation for project-id: "
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
          :error-handler (fn [response]
                           (reset! loading? false)
                           ($ js/console log
                              "[Error] retrieving compensation-owed project-id: "
                              project-id))})))

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
           :handler (fn [response]
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
          :error-handler (fn [response]
                           (reset! loading? false)
                           ($ js/console log
                              "[Error] retrieving default-compensation for project-id: "
                              project-id))})))

(reg-event-fx
 :project/get-funds
 [trim-v]
 (fn [cofx event]
   (let [project-id @(subscribe [:active-project-id])
         project-funds (r/cursor state [:project-funds])]
     (GET "/api/project-funds"
          {:params {:project-id project-id}
           :headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :handler #(reset! project-funds (get-in % [:result :project-funds]))
           :error-handler #($ js/console log "[Error] retrieving get-project-funds")}))
   {}))

(defn check-pending-transactions
  "Check the pending transactions on the server"
  []
  (PUT "/api/check-pending-transaction"
       {:params {:project-id @(subscribe [:active-project-id])}
        :headers {"x-csrf-token" @(subscribe [:csrf-token])}
        :handler #(dispatch [:project/get-funds])
        :error-handler #($ js/console log "[[check-pending-transaction]]: error " %)}))

(defn rate->string
  "Convert a rate to a human readable string"
  [rate]
  (str (acct/cents->string (:amount rate)) " / " (:item rate))
  #_ (str (acct/cents->string (:amount rate)) " / " (:item rate)
          " + " (admin-fee-text (:amount rate) admin-fee)))

(defn ProjectFunds [state]
  (let [project-funds (r/cursor state [:project-funds])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [current-balance compensation-outstanding available-funds
                      admin-fees pending-funds]} @project-funds]
          [:div
           [:div.ui.segment
            [:div
             [:h4.ui.dividing.header "Project Funds"]
             [:div.ui.grid
              [:div.ui.row
               [:div.five.wide.column
                "Available Funds: "]
               [:div.eight.wide.column]
               [:div.three.wide.column
                {:style {:text-align "right"}}
                (acct/cents->string available-funds)]]
              [:div.ui.row
               [:div.five.wide.column
                "Outstanding Compensations: "]
               [:div.eight.wide.column]
               [:div.three.wide.column
                {:style {:text-align "right"}}
                (acct/cents->string compensation-outstanding)]]
              [:div.ui.row
               [:div.five.wide.column
                "Outstanding Admin Fees: "]
               [:div.eight.wide.column]
               [:div.three.wide.column
                {:style {:text-align "right"}}
                (acct/cents->string admin-fees)]]
              [:div.ui.row
               [:div.five.wide.column
                "Current Balance: "]
               [:div.eight.wide.column]
               [:div.three.wide.column
                {:style {:text-align "right"}}
                (acct/cents->string current-balance)]]]]]
           (when (> pending-funds 0)
             [:div.ui.segment
              [:div
               [:h4.ui.dividing.header "Awaiting Approval"]
               [:div.ui.grid
                [:div.ui.row
                 {:style {:color "red"}}
                 [:div.five.wide.column
                  "Funds Pending: "]
                 [:div.eight.wide.column]
                 [:div.three.wide.column
                  {:style {:text-align "right"}}
                  (acct/cents->string pending-funds)]]]]])]))
      :get-initial-state (fn [this]
                           (when-not (nil? @project-funds)
                             (reset! project-funds nil))
                           (dispatch [:project/get-funds]))
      :component-did-update (fn [this old-argv]
                              ;; do the initial check
                              (check-pending-transactions)
                              ;; TODO: this starts a new timer each time ratom inputs change?
                              (let [pending-funds (r/cursor state [:project-funds :pending-funds])]
                                (util/continuous-update-until check-pending-transactions
                                                              #(= @pending-funds 0)
                                                              (constantly nil)
                                                              check-pending-interval)))})))

(defn ToggleCompensationEnabled [{:keys [compensation-id] :as compensation}]
  (let [project-id @(subscribe [:active-project-id])
        compensation-atom (r/cursor state [:project-compensations compensation-id])
        enabled (r/cursor compensation-atom [:enabled])
        updating? (r/cursor compensation-atom [:updating?])]
    [Button {:toggle true
             :active @enabled
             :disabled @updating?
             :on-click (fn [_]
                         (swap! enabled not)
                         (reset! updating? true)
                         (PUT "/api/toggle-compensation-enabled"
                              {:params {:project-id project-id
                                        :compensation-id compensation-id
                                        :enabled @enabled}
                               :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                               :handler (fn [response]
                                          (reset! updating? false)
                                          (get-compensations! state))
                               :error-handler (fn [response]
                                                ($ js/console log
                                                   (str "[Error] " "update-compensation!"))
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
                 :handler (fn [response]
                            (reset! creating? false)
                            (get-compensations! state)
                            (reset! compensation-amount "$0.00"))
                 :error-handler (fn [error]
                                  (reset! error-message (get-in error [:response :error :message]))
                                  (reset! creating? false))}))]
    (r/create-class
     {:reagent-render
      (fn []
        [s/Form {:on-submit #(let [cents (acct/string->cents @compensation-amount)]
                               (cond (not (re-matches acct/valid-usd-regex @compensation-amount))
                                     (reset! error-message "Amount is not valid")
                                     (= cents 0)
                                     (reset! compensation-amount "$0.00")
                                     (> cents 0)
                                     (create-compensation! cents)
                                     :else
                                     (reset! compensation-amount (acct/cents->string cents))))}
         [:div.ui.relaxed.divided.list
          (when @error-message
            [s/Message {:onDismiss #(reset! error-message nil)
                        :negative true}
             [s/MessageHeader "Creation Compensation Error"]
             @error-message])
          [:div.item {:key "create"}
           [:div {:style {:width "6em"
                          :display "inline-block"}}
            [s/FormInput {:id "create-compensation-amount"
                          :value @compensation-amount
                          :on-change #(let [value (-> ($ % :target.value) (sutil/ensure-prefix "$"))]
                                        (reset! error-message nil)
                                        (reset! compensation-amount value))
                          :aria-label "compensation-amount"}]]
           (let [cents-amount (acct/string->cents @compensation-amount)]
             (when-not (= 0 cents-amount)
               [:div {:style {:display "inline-block"
                              :margin-left "0.5em"}}
                [AdminFee cents-amount admin-fee]
                [:span {:style {:font-size "1.3em"}} " / article"]]))
           [:div.right.floated.content
            [Button {:color "blue"
                     :disabled (or @creating? @loading?)}
             "Create"]]]]])
      :component-will-mount (fn [_]
                              (reset! compensation-amount "$0.00")
                              (reset! error-message nil))})))

(defn ProjectCompensations []
  (let [project-id @(subscribe [:active-project-id])
        loading? (r/cursor state [:loading :project-compensations])
        creating? (r/cursor state [:creating-new-compensation?])]
    (r/create-class
     {:reagent-render
      (fn []
        (let [project-compensations (->> @(r/cursor state [:project-compensations])
                                         vals
                                         (sort-by #(get-in % [:rate :amount])))]
          [:div#project-compensations.ui.segment
           [:h4.ui.dividing.header "Project Compensation"]
           ;; display the current compensations
           (when-not (empty? project-compensations)
             [:div.ui.relaxed.divided.list
              (doall
               (for [c project-compensations]
                 [:div.item {:key (:compensation-id c)}
                  [:div.right.floated.content
                   [ToggleCompensationEnabled c]]
                  [:div.content {:style {:padding-top "4px"
                                         :padding-bottom "4px"}}
                   [CompensationAmount c admin-fee]]]))])
           [:h4.ui.dividing.header "Create New Compensation"]
           [CreateCompensationForm]]))
      :component-did-mount (fn [_] (get-compensations! state))})))

(defn CompensationGraph
  "Labels is a list of names, amount-owed is a vector of amounts owed."
  [labels amount-owed]
  (let [font-color (charts/graph-text-color)
        data {:labels labels
              :datasets [{:data amount-owed
                          :backgroundColor (nth charts/paul-tol-colors (count labels))}]}
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
                 :tooltips {:callbacks {:label #(acct/cents->string ($ % :yLabel))}}}]
    [chartjs/bar
     {:data data
      :height (+ 50 (* 12 (count labels)))
      :width (* (count labels) 200)
      :options options}]))

(defn CompensationSummary []
  (let [compensation-owed (r/cursor state [:compensation-owed])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when-not (empty? @compensation-owed)
          [:div.ui.segment
           [:h4.ui.dividing.header "Compensation Owed"]
           [:div.ui.relaxed.divided.list
            (doall
             (map
              (fn [user-owed]
                (let [{:keys [compensation-owed last-payment connected
                              user-id admin-fee username]} user-owed
                      retrieving-amount-owed? @(r/cursor state [:loading :compensation-owed])
                      confirming? (r/cursor state [:confirming? user-id])
                      retrieving-pay? @(r/cursor state [:retrieving-pay? user-id])
                      error-message (r/cursor state [:pay-error user-id])]
                  [:div.item {:key username}
                   (when @confirming?
                     [:div.ui.message {:position "absolute"}
                      [:div.ui.grid
                       [:div.ui.row
                        [:div.five.wide.column
                         "Compensation: "]
                        [:div.eight.wide.column]
                        [:div.three.wide.column.right.aligned (acct/cents->string compensation-owed)]]
                       [:div.ui.row
                        [:div.five.wide.column
                         "Admin Fees: "]
                        [:div.eight.wide.column]
                        [:div.three.wide.column.right.aligned (acct/cents->string admin-fee)]]
                       [:div.ui.row
                        [:div.five.wide.column
                         "Total to be Deducted: "]
                        [:div.eight.wide.column]
                        [:div.three.wide.column.right.aligned
                         [:span.bold (acct/cents->string (+ compensation-owed admin-fee))]]]
                       [:div.ui.row
                        [:div.eight.wide.column]
                        [:div.four.wide.column
                         [Button
                          {:on-click #(pay-user! state user-id compensation-owed admin-fee)
                           :disabled (or retrieving-pay? retrieving-amount-owed?)
                           :color "blue"
                           :class "fluid"}
                          "Confirm"]]
                        [:div.four.wide.column
                         [Button {:on-click #(do (reset! confirming? false)
                                                 (reset! error-message nil))
                                  :class "fluid"}
                          "Cancel"]]]
                       (when @error-message
                         [:div.ui.red.message @error-message])]])
                   [:div.ui.grid
                    [:div.five.wide.column
                     [:i.user.icon]
                     username]
                    [:div.two.wide.column
                     (acct/cents->string compensation-owed)]
                    [:div.four.wide.column
                     (when last-payment
                       (str "Last Payment: " (util/unix-epoch->date-string last-payment)))]
                    [:div.five.wide.column.right.align
                     (cond
                       (and (> compensation-owed 0)
                            (not @confirming?))
                       [Button {:on-click #(reset! confirming? true)
                                :color "blue"
                                :disabled (or retrieving-pay?
                                              retrieving-amount-owed?)}
                        "Pay"])]]]))
              @compensation-owed))]]))
      :component-did-mount (fn [_] (compensation-owed! state))})))

(defn compensation-options
  [project-compensations]
  (conj (->> (vals project-compensations)
             (sort-by #(get-in % [:rate :amount]))
             (filter :enabled)
             (map (fn [compensation]
                    {:text (rate->string (:rate compensation))
                     :value (:compensation-id compensation)})))
        {:text "No Compensation"
         :value "none"}))

(defn UserCompensationDropdown [user-id]
  (let [project-id @(subscribe [:active-project-id])
        project-compensations (r/cursor state [:project-compensations])
        user-atom (r/cursor state [:project-users-current-compensation user-id])
        compensation-id (r/cursor user-atom [:compensation-id])
        updating? (r/cursor user-atom [:updating?])
        loading? (r/cursor state [:loading :project-users-current-compensation])]
    [Dropdown {:fluid true
               :options (compensation-options @project-compensations)
               :selection true
               :disabled (or @updating? @loading?)
               :loading @updating?
               :value @compensation-id
               :on-change (fn [event data]
                            (let [value ($ data :value)]
                              (when-not (= value @compensation-id)
                                (reset! compensation-id value)
                                (reset! updating? true)
                                (PUT "/api/set-user-compensation"
                                     {:params {:project-id project-id
                                               :compensation-id value
                                               :user-id user-id}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [response]
                                                 (reset! updating? false)
                                                 (get-project-users-current-compensation! state))
                                      :error-handler (fn [error]
                                                       (reset! updating? false))}))))}]))

(defn DefaultCompensationDropdown []
  (let [project-id @(subscribe [:active-project-id])
        project-compensations (r/cursor state [:project-compensations])
        default-compensation (r/cursor state [:default-project-compensation])
        updating? (r/cursor state [:updating :project-compensations])
        loading? (r/cursor state [:loading :project-compensations])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Dropdown {:fluid true
                   :options (compensation-options @project-compensations)
                   :selection true
                   :loading @updating?
                   :disabled (or @updating? @loading?)
                   :value @default-compensation
                   :on-change (fn [event data]
                                (let [value ($ data :value)]
                                  (when-not (= value @default-compensation)
                                    (reset! default-compensation value)
                                    (reset! updating? true)
                                    (PUT "/api/set-default-compensation"
                                         {:params {:project-id project-id
                                                   :compensation-id (if (= value "none")
                                                                      nil
                                                                      value)}
                                          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                          :handler (fn [response]
                                                     (get-default-compensation! state)
                                                     (reset! updating? false))
                                          :error-handler (fn [error]
                                                           (reset! updating? false))}))))}])
      :component-did-mount
      (fn [_] (get-default-compensation! state))})))

(defn UsersCompensations []
  (let [project-compensations (r/cursor state [:project-compensations])
        current-compensation (r/cursor state [:project-users-current-compensation])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when (and @project-compensations @current-compensation)
          [:div.ui.segment
           [:div
            [:h4.ui.dividing.header "User Compensations"]
            [:div.ui.relaxed.divided.list
             [:div.item {:key "default-compensation"}
              [:div.right.floated.content
               [DefaultCompensationDropdown]]
              [:div.content {:style {:padding-bottom "4px"}}
               [:i.user.icon] "Default New User Compensation"]]
             (doall
              (for [{:keys [user-id email]} (vals @current-compensation)]
                [:div.item {:key user-id}
                 [:div.right.floated.content
                  [UserCompensationDropdown user-id]]
                 [:div.content {:style {:padding-top "4px"}}
                  [:i.user.icon] email]]))]]]))
      :component-did-mount
      (fn [_] (get-project-users-current-compensation! state))})))

(defmethod panel-content [:project :project :compensations] []
  (fn [child]
    [:div.project-content
     [:div.ui.one.column.stack.grid
      [:div.ui.row
       [:div.ui.column
        [ProjectFunds state]]]
      [:div.ui.row
       [:div.ui.column
        #_ [support/SupportFormOnce support/state]
        [paypal/AddFunds]]]]
     [:div.ui.two.column.stack.grid
      [:div.ui.row
       [:div.ui.column [ProjectCompensations]]
       [:div.ui.column [UsersCompensations]]]]
     [CompensationSummary]]))
