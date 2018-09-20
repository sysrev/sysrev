(ns sysrev.views.panels.project.compensation
  (:require [ajax.core :refer [POST GET DELETE PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.charts.chartjs :as chartjs]
            [sysrev.util :refer [vector->hash-map]]
            [sysrev.views.charts :as charts]
            [sysrev.views.semantic :refer [Form FormGroup FormInput Button Dropdown]])
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

(defn amount-owed!
  "Retrieve what is owed to users from start-date to end-date"
  [state start-date end-date]
  (let [project-id @(subscribe [:active-project-id])
        retrieving-amount-owed? (r/cursor state [:retrieving-amount-owed?])
        amount-owed (r/cursor state [:amount-owed])]
    (reset! retrieving-amount-owed? true)
    (GET "/api/amount-owed"
         {:params {:project-id project-id
                   :start-date start-date
                   :end-date end-date}
          :headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-amount-owed? false)
                     (reset! amount-owed (->> (get-in response [:result :amount-owed])
                                              (group-by :compensation-id))))
          :error-handler (fn [error-response]
                           (reset! retrieving-amount-owed? false)
                           ($ js/console log "[Error] retrieving amount-owed project-id: " project-id))})))

(defn get-default-compensation!
  "Get the default current compensation and set it"
  [state]
    (let [project-id @(subscribe [:active-project-id])
          retrieving? (r/cursor state [:project-compensations :retrieving?])
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
(defn rate->string
  "Convert a rate to a human readable string"
  [rate]
  (str (accounting/cents->string (:amount rate)) " / " (:item rate)))

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

#_(defn ProjectCompensation
  "Display a project compensation"
  [compensation]
  (let []
    #_[:div
     [:div [EditCompensationForm compensation]
      [:div.ui.button {:on-click #(reset! editing? false)} "Dismiss"]]]
    [:div (accounting/cents->string (get-in compensation [:rate :amount])) " per Article"
     #_[Button {:toggle true
              :active @active?
              :disabled @updating-compensation?
              :on-click toggle-active}
      (if @active? "Active" "Disabled")]]
    #_[:div.ui.button {:on-click delete-compensation!} "Delete"]))

(defn CreateCompensationForm
  []
  (let [project-id @(subscribe [:active-project-id])
        compensation-amount (r/cursor state [:compensation-amount])
        creating-new-compensation? (r/cursor state [:creating-new-compensation?])
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
         [:div
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
                         :on-change on-change}])]
          [:div {:style {:display "inline-block"
                         :margin-left "1em"}} "per Article"]
          [:button {:style {:margin-left "1em"}
                    :class (str "ui button primary")}
           "Create"]]])
      :component-will-mount (fn [this]
                              (reset! compensation-amount "$0.00"))})))

(defn ProjectCompensations
  []
  (let [project-id @(subscribe [:active-project-id])
        retrieving-compensations? (r/cursor state [:retrieving-compensations?])
        creating-new-compensation? (r/cursor state [:creating-new-compensation?])
        ]
    (r/create-class
     {:reagent-render
      (fn []
        (let [project-compensations (->> @(r/cursor state [:project-compensations])
                                         vals
                                         (sort-by #(get-in % [:rate :amount])))]
          [:div.ui.segment
           [:h4.ui.dividing.header "Project Compensation"]
           ;; display the current compensations
           (when-not (nil? project-compensations)
             [:div.ui.relaxed.divided.list
              (map
               (fn [compensation]
                 [:div.item {:key (:id compensation)}
                  [:div.right.floated.content
                   [ToggleCompensationActive compensation]]
                  [:div.content {:style {:padding-top "4px"
                                         :padding-bottom "4px"}}
                   [:div (accounting/cents->string (get-in compensation [:rate :amount])) " per Article"]]])
               project-compensations)])
           [:h4.ui.dividing.header "Create New Compensation"]
           ;; the form for compensations
           (if (or @creating-new-compensation?
                   @retrieving-compensations?)
             [:div {:class "ui active centered inline loader"}]
             [CreateCompensationForm])]))
      :component-did-mount (fn [this]
                             (get-compensations! state))})))
(defn CompensationGraph
  "Labels is a list of names, amount-owed is a vector of amounts owed "
  [labels amount-owed]
  (let [font-color (charts/graph-text-color)
        data {:labels labels
              :datasets [{:data amount-owed
                          :backgroundColor (nth charts/paul-tol-colors (count labels))}]}
        options (charts/wrap-disable-animation
                 {:scales
                  {:xAxes
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
                                                    value))
                                           (accounting/cents->string value)
                                           ""))}}]
                   :yAxes
                   [{:maxBarThickness 10
                     :scaleLabel {:fontColor font-color}
                     :ticks {:fontColor font-color}}]}
                  :legend
                  {:display false}
                  :tooltips {:callbacks {:label (fn [item]
                                                  (accounting/cents->string ($ item :xLabel)))}}})]
    [chartjs/horizontal-bar
     {:data data
      :height (charts/label-count->chart-height (count labels))
      :options options}]))

(defn CompensationSummary
  []
  (let [amount-owed (r/cursor state [:amount-owed])]
    (amount-owed! state "2018-9-1" "2018-9-30")
    (when-not (empty? (keys @amount-owed))
      [:div.ui.segment
       [:h4.ui.dividing.header "Compensation Summary"]
       (let [users-owed-map (->> @(r/cursor state [:amount-owed])
                                 vals
                                 flatten
                                 (group-by :name))
             total-owed (fn [owed-vectors]
                          (apply +
                                 (map #(* (:articles %) (get-in % [:rate :amount])) owed-vectors)))
             total-owed-to-users (zipmap (keys users-owed-map) (->> users-owed-map vals (map total-owed)))
             total-owed-maps (->> (keys total-owed-to-users)
                                  (map #(hash-map :name % :owed (get total-owed-to-users %)))
                                  (filter #(> (% :owed) 0))
                                  (sort-by :name))
             labels (map :name total-owed-maps)
             data (map :owed total-owed-maps)]
         [:div
          [:h4 "Total Owed"]
          [CompensationGraph labels data]])
       (doall (map
               (fn [compensation-id]
                 (let [compensation-owed (r/cursor state [:amount-owed compensation-id])
                       rate (-> @compensation-owed
                                first
                                :rate)
                       amount-owed (fn [compensation-map]
                                     (* (get-in compensation-map [:rate :amount])
                                        ;; note: this will need to be changed for
                                        ;; items other than articles
                                        (:articles compensation-map)))
                       compensation-maps (->> @compensation-owed
                                              (filter #(> (% :articles) 0))
                                              (sort-by :name))
                       total-owed (apply + (map amount-owed compensation-maps))
                       labels (map :name compensation-maps)]
                   (when (> total-owed 0)
                     ^{:key compensation-id}
                     [:div
                      [:h4 "Total owed at " (accounting/cents->string (:amount rate)) " / " (:item rate) " :    " (accounting/cents->string total-owed)]
                      [CompensationGraph labels (mapv amount-owed compensation-maps)]])))
               (keys @amount-owed)))])))

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
        updating? (r/cursor user-compensation [:updating?])]
    [Dropdown {:fluid true
               :options (compensation-options @project-compensations)
               :selection true
               :loading @updating?
               :value @current-compensation-id
               :on-change (fn [event data]
                            (let [value ($ data :value)]
                              (when-not (= value current-compensation-id)
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
        updating? (r/cursor state [:project-compensations :updating?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [Dropdown {:fluid true
                   :options (compensation-options @project-compensations)
                   :selection true
                   :loading @updating?
                   :value @default-project-compensation
                   :on-change (fn [event data]
                                (let [value ($ data :value)]
                                  (when-not (= value default-project-compensation)
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
    (get-project-users-current-compensation! state)
    (get-compensations! state)
    ;;(get-default-compensation! state)
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
           (->> (vals @project-users-current-compensations))
           ))]]])))
