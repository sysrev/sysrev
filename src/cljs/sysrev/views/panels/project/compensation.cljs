(ns sysrev.views.panels.project.compensation
  (:require [ajax.core :refer [POST GET DELETE PUT]]
            [cljsjs.moment]
            [clojure.string :as string]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx trim-v dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.charts.chartjs :as chartjs]
            [sysrev.util :refer [vector->hash-map]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.charts :as charts]
            [sysrev.paypal :refer [PayPalButton]]
            [sysrev.views.semantic :refer [Form FormGroup FormInput Button Dropdown]]
            [sysrev.views.panels.project.support :as support :refer [SupportFormOnce]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:project :project :compensation])

;; set this to defonce when done developing
(def state (r/cursor app-db [:state :panels panel]))

(defn get-compensations!
  "Retrieve the current compensations"
  [state]
  (let [project-id @(subscribe [:active-project-id])
        retrieving-compensations? (r/cursor state [:retrieving-compensations?])
        project-compensations (r/cursor state [:project-compensations])]
    (reset! retrieving-compensations? true)
    (GET "/api/project-compensations"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-compensations? false)
                     (reset! project-compensations (vector->hash-map (get-in response [:result :compensations])
                                                                     :id)))
          :error-handler (fn [error-response]
                           (reset! retrieving-compensations? false)
                           ($ js/console log "[Error] retrieving for project-id: " project-id))})))

(defn get-project-users-current-compensation!
  "Retrieve the current compensations for project users"
  [state]
  (let [project-id @(subscribe [:active-project-id])
        retrieving-project-users-current-compensation? (r/cursor state [:retrieving-project-users-current-compensation?])
        project-users-current-compensation (r/cursor state [:project-users-current-compensations])]
    (reset! retrieving-project-users-current-compensation? true)
    (GET "/api/project-users-current-compensation"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-project-users-current-compensation? false)
                     (reset! project-users-current-compensation
                             (vector->hash-map (->> (get-in response [:result :project-users-current-compensation])
                                                    ;; set compensation-id nil = none
                                                    (map #(update % :compensation-id
                                                                  (fn [val]
                                                                    (if (nil? val)
                                                                      "none"
                                                                      val)))))
                                               :user-id)))
          :error-handler (fn [error-response]
                           (reset! retrieving-project-users-current-compensation? false)
                           ($ js/console log "[Error] retrieving project-users-current-compensation for project-id: " project-id))})))

(defn compensation-owed!
  "Retrieve what is owed to users from start-date to end-date"
  [state]
  (let [project-id @(subscribe [:active-project-id])
        retrieving-amount-owed? (r/cursor state [:retrieving-amount-owed?])
        compensation-owed (r/cursor state [:compensation-owed])]
    (reset! retrieving-amount-owed? true)
    (GET "/api/compensation-owed"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-amount-owed? false)
                     (reset! compensation-owed (get-in response [:result :compensation-owed])))
          :error-handler (fn [error-response]
                           (reset! retrieving-amount-owed? false)
                           ($ js/console log "[Error] retrieving compensation-owed project-id: " project-id))})))

(defn pay-user!
  "Pay the user the amount owed to them"
  [state user-id amount]
  (let [project-id @(subscribe [:active-project-id])
        retrieving? (r/cursor state [:retrieving-pay? user-id])
        pay-error (r/cursor state [:pay-error user-id])]
    (reset! retrieving? true)
    (reset! pay-error nil)
    (POST "/api/pay-user"
          {:params {:project-id project-id
                    :user-id user-id
                    :amount (int amount)}
           :headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :handler (fn [response]
                      (reset! retrieving? false)
                      (compensation-owed! state)
                      (dispatch [:project/get-funds]))
           :error-handler (fn [error-response]
                            (reset! retrieving? false)
                            (compensation-owed! state)
                            (.log js/console "pay error: " (clj->js error-response))
                            (reset! pay-error (get-in error-response [:response :error :message])))})))

(defn get-default-compensation!
  "Get the default current compensation and set it"
  [state]
    (let [project-id @(subscribe [:active-project-id])
          retrieving? (r/cursor state [:retrieving-project-compensations?])
          default-compensation (r/cursor state [:default-project-compensation])]
    (reset! retrieving? true)
    (GET "/api/get-default-compensation"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (let [compensation-id (get-in response [:result :compensation-id])]
                       (reset! retrieving? false)
                       (reset! default-compensation (if (nil? compensation-id)
                                                      "none"
                                                      compensation-id))))
          :error-handler (fn [error-response]
                           (reset! retrieving? false)
                           ($ js/console log "[Error] retrieving default-compensation for project-id: " project-id))})))
(reg-event-fx
 :project/get-funds
 [trim-v]
 (fn [cofx event]
    (let [project-id @(subscribe [:active-project-id])
          project-funds (r/cursor state [:project-funds])]
    (GET "/api/project-funds"
         {:params {:project-id project-id}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! project-funds (get-in response [:result :project-funds])))
          :error-handler (fn [error-response]
                           ($ js/console log "[Error] retrieving get-project-funds"))}))
   {}))

(defn rate->string
  "Convert a rate to a human readable string"
  [rate]
  (str (accounting/cents->string (:amount rate)) " / " (:item rate)))

(defn ProjectFunds
  [state]
  (let [project-funds (r/cursor state [:project-funds])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [current-balance compensation-outstanding available-funds]}
              @project-funds]
          [:div.ui.segment
           [:div
            [:h4.ui.dividing.header "Project Funds"]
            [:div.ui.grid
             [:div.ui.row
              [:div.five.wide.column
               "Available Funds: "]
              [:div.eight.wide.column]
              [:div.three.wide.column
               (accounting/cents->string current-balance)]]
             [:div.ui.row
              [:div.five.wide.column
               "Compensations Outstanding: "]
              [:div.eight.wide.column]
              [:div.three.wide.column
               (accounting/cents->string compensation-outstanding)]]
             [:div.ui.row
              [:div.five.wide.column
               "Current Balance: "]
              [:div.eight.wide.column]
              [:div.three.wide.column
               (accounting/cents->string available-funds)]]]]]))
      :get-initial-state
      (fn [this]
        (when-not (nil? @project-funds)
          (reset! project-funds nil))
        (dispatch [:project/get-funds]))})))

(defn ToggleCompensationActive
  [compensation]
  (let [project-id @(subscribe [:active-project-id])
        compensation-atom (r/cursor state [:project-compensations (:id compensation)])
        active? (r/cursor compensation-atom [:active])
        updating-compensation? (r/cursor compensation-atom [:updating-compensation?])
        toggle-active (fn [cents]
                        (swap! active? not)
                        (reset! updating-compensation? true)
                        (PUT "/api/toggle-compensation-active"
                             {:params {:project-id project-id
                                       :compensation-id (:id compensation)
                                       :active @active?}
                              :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                              :handler (fn [response]
                                         (reset! updating-compensation? false)
                                         (get-compensations! state))
                              :error-handler (fn [error]
                                               ($ js/console log (str "[Error] " "update-compensation!"))
                                               (reset! updating-compensation? false))}))]
    [Button {:toggle true
              :active @active?
              :disabled @updating-compensation?
              :on-click toggle-active}
     (if @active? "Active" "Disabled")]))

(defn CreateCompensationForm
  []
  (let [project-id @(subscribe [:active-project-id])
        compensation-amount (r/cursor state [:compensation-amount])
        creating-new-compensation? (r/cursor state [:creating-new-compensation?])
        retrieving-compensations? (r/cursor state [:retrieving-compensations?])
        create-compensation! (fn [cents]
                               (reset! creating-new-compensation? true)
                               (POST "/api/project-compensation"
                                     {:params {:project-id project-id
                                               :rate {:item "article"
                                                      :amount cents}}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [response]
                                                 (reset! creating-new-compensation? false)
                                                 (get-compensations! state)
                                                 (reset! compensation-amount "$0.00"))
                                      :error-handler (fn [error]
                                                       ($ js/console log (str "[Error] " "create-compensation!"))
                                                       (reset! creating-new-compensation? false))}))]
    (r/create-class
     {:reagent-render
      (fn []
        [Form {:on-submit (fn []
                            (let [cents (accounting/string->cents @compensation-amount)]
                              (cond (= cents 0)
                                    (reset! compensation-amount "$0.00")
                                    (> cents 0)
                                    (create-compensation! cents)
                                    :else
                                    (reset! compensation-amount (accounting/cents->string cents)))))}
         [:div.ui.relaxed.divided.list
          [:div.item {:key "create"}
           [:div {:style {:width "6em"
                          :display "inline-block"}}
            (let [on-change (fn [event]
                              (let [value ($ event :target.value)
                                    dollar-sign-on-front? (fn [value]
                                                            (= ($ value indexOf "$")
                                                               0))
                                    new-value (cond
                                                (not (dollar-sign-on-front? value))
                                                (str "$" ($ event :target.value))
                                                :else
                                                value)]
                                (reset! compensation-amount new-value)))]
              [FormInput {:value @compensation-amount
                          :id "create-compensation-amount"
                          :on-change on-change}])]
           [:div {:style {:display "inline-block"
                          :margin-left "1em"}} "per Article"]
           [:div.right.floated.content
            [Button {:disabled (or @creating-new-compensation?
                                   @retrieving-compensations?)
                     :color "blue"}
             "Create"]]]]])
      :component-will-mount (fn [this]
                              (reset! compensation-amount "$0.00"))})))

(defn ProjectCompensations
  []
  (let [project-id @(subscribe [:active-project-id])
        retrieving-compensations? (r/cursor state [:retrieving-compensations?])
        creating-new-compensation? (r/cursor state [:creating-new-compensation?])]
    (r/create-class
     {:reagent-render
      (fn []
        (let [project-compensations (->> @(r/cursor state [:project-compensations])
                                         vals
                                         (sort-by #(get-in % [:rate :amount])))]
          [:div.ui.segment
           [:h4.ui.dividing.header "Project Compensation"]
           ;; display the current compensations
           (when-not (empty? project-compensations)
             [:div.ui.relaxed.divided.list
              (map
               (fn [compensation]
                 [:div.item {:key (:id compensation)}
                  [:div.right.floated.content
                   [ToggleCompensationActive compensation]]
                  [:div.content {:style {:padding-top "4px"
                                         :padding-bottom "4px"}}
                   [:div (str (accounting/cents->string (get-in compensation [:rate :amount])) " per Article")]]])
               project-compensations)])
           [:h4.ui.dividing.header "Create New Compensation"]
           [CreateCompensationForm]]))
      :component-did-mount (fn [this]
                             (get-compensations! state))})))

(defn CompensationGraph
  "Labels is a list of names, amount-owed is a vector of amounts owed "
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
                                          (accounting/cents->string value)
                                          ""))}}]
                  :xAxes
                  [{:maxBarThickness 10
                    :scaleLabel {:fontColor font-color}
                    :ticks {:fontColor font-color}}]}
                 :legend
                 {:display false}
                 :tooltips {:callbacks {:label (fn [item]
                                                 (accounting/cents->string ($ item :yLabel)))}}}]
    [chartjs/bar
     {:data data
      :height (charts/label-count->chart-height (count labels))
      :width (* (count labels) 200)
      :options options}]))

(defn CompensationSummary
  []
  (let [compensation-owed (r/cursor state [:compensation-owed])
        unix-epoch->date-string (fn [unix]
                                  (-> unix
                                      (js/moment.unix)
                                      ($ format "YYYY-MM-DD HH:mm:ss")))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when-not (empty? @compensation-owed)
          [:div.ui.segment
           [:h4.ui.dividing.header "Compensation Owed"]
           [:div.ui.relaxed.divided.list
            (doall (map
                    (fn [user-owed]
                      (let [user-name (-> (:email user-owed)
                                          (string/split #"@")
                                          first)
                            email (:email user-owed)
                            amount-owed (:amount-owed user-owed)
                            last-payment (:last-payment user-owed)
                            connected? (:connected user-owed)
                            user-id (:user-id user-owed)
                            pay-disabled? @(r/cursor state [:retrieving-pay? user-id])
                            error-message (r/cursor state [:pay-error user-id])]
                        [:div.item {:key user-name}
                         [:div.ui.grid
                          [:div.five.wide.column
                           [:i.user.icon]
                           email]
                          [:div.two.wide.column
                           (accounting/cents->string amount-owed)]
                          [:div.four.wide.column
                           (when last-payment
                             (str "Last Payment: " (unix-epoch->date-string last-payment)))]
                          [:div.five.wide.column.right.align
                           (cond
                             ;; (and (> amount-owed 0)
                             ;;      (not connected?))
                             ;; [Button {:disabled true
                             ;;          :color "blue"}
                             ;;  "No Payment Destination"]
                             (> amount-owed 0)
                             [Button {:on-click #(pay-user! state user-id amount-owed)
                                      :color "blue"
                                      :disabled pay-disabled?}
                              "Pay"])
                           (when @error-message
                             [:div {:class "ui red message"}
                              @error-message])]]]))
                    @compensation-owed))]]))
      :component-did-mount (fn [this]
                             (compensation-owed! state))})))

(defn compensation-options
  [project-compensations]
  (conj (->> (vals project-compensations)
             (sort-by #(get-in % [:rate :amount]))
             (filter :active)
             (map (fn [compensation]
                    {:text (rate->string (:rate compensation))
                     :value (:id compensation)})))
        {:text "No Compensation"
         :value "none"}))

(defn UserCompensationDropdown [user-id]
  (let [project-id @(subscribe [:active-project-id])
        project-compensations (r/cursor state [:project-compensations])
        project-users-current-compensation (r/cursor state [:project-users-current-compensations])
        user-compensation (r/cursor project-users-current-compensation [user-id])
        current-compensation-id (r/cursor user-compensation [:compensation-id])
        updating? (r/cursor user-compensation [:updating?])
        retrieving-project-users-current-compensation? (r/cursor state [:retrieving-project-users-current-compensation?])]
    [Dropdown {:fluid true
               :options (compensation-options @project-compensations)
               :selection true
               :disabled (or @updating? @retrieving-project-users-current-compensation?)
               :loading @updating?
               :value @current-compensation-id
               :on-change (fn [event data]
                            (let [value ($ data :value)]
                              (when-not (= value current-compensation-id)
                                (reset! current-compensation-id value)
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
        default-project-compensation (r/cursor state [:default-project-compensation])
        updating? (r/cursor state [:updating-project-compensations?])
        retrieving-default-compensations? (r/cursor state [:retrieving-project-compensations?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Dropdown {:fluid true
                   :options (compensation-options @project-compensations)
                   :selection true
                   :loading @updating?
                   :disabled (or @updating? @retrieving-default-compensations?)
                   :value @default-project-compensation
                   :on-change (fn [event data]
                                (let [value ($ data :value)]
                                  (when-not (= value default-project-compensation)
                                    (reset! default-project-compensation value)
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
      (fn [this]
        (get-default-compensation! state))})))

(defn UsersCompensations []
  (let [project-users-current-compensations
        (r/cursor state [:project-users-current-compensations])
        project-compensations (r/cursor state [:project-compensations])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (when (and @project-compensations
                   @project-users-current-compensations)
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
              (map
               (fn [user-compensation-map]
                 [:div.item {:key (:user-id user-compensation-map)}
                  [:div.right.floated.content
                   [UserCompensationDropdown (:user-id user-compensation-map)]]
                  [:div.content
                   {:style {:padding-top "4px"}}
                   [:i.user.icon]
                   (:email user-compensation-map)]])
               (->> (vals @project-users-current-compensations))))]]]))
      :component-did-mount
      (fn [this]
        (get-project-users-current-compensation! state))})))

(defmethod panel-content [:project :project :compensations] []
  (fn [child]
    [:div.project-content
     [:div.ui.one.column.stack.grid
      [:div.ui.row
       [:div.ui.column
        [ProjectFunds state]]]
      [:div.ui.row
       [:div.ui.column
        ;;[SupportFormOnce support/state]
        [PayPalButton]
        ]]]
     [:div.ui.two.column.stack.grid
      [:div.ui.row
       [:div.ui.column [ProjectCompensations]]
       [:div.ui.column [UsersCompensations]]]]
     [CompensationSummary]]))
