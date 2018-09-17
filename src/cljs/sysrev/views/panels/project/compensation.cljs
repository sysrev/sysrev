(ns sysrev.views.panels.project.compensation
  (:require [ajax.core :refer [POST GET DELETE PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.charts.chartjs :as chartjs]
            [sysrev.util :refer [vector->hash-map]]
            [sysrev.views.charts :as charts]
            [sysrev.views.semantic :refer [Form FormGroup FormInput Button]])
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

#_(defn EditCompensationForm
  [compensation]
  (let [project-id @(subscribe [:active-project-id])
        compensation-atom (r/cursor state [:project-compensations (:id compensation)])
        compensation-amount (r/cursor compensation-atom [:rate :amount])
        updating-compensation? (r/cursor compensation-atom [:updating-compensation?])
        update-compensation! (fn [cents]
                               (reset! updating-compensation? true)
                               (PUT "/api/project-compensation"
                                    {:params {:project-id project-id
                                              :compensation-id (:id compensation)
                                              :rate {:item "article"
                                                     :amount cents}}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [response]
                                                 (reset! updating-compensation? false)
                                                 (get-compensations! state)
                                                 (.log js/console "success"))
                                      :error-handler (fn [error]
                                                       ($ js/console log (str "[Error] " "update-compensation!"))
                                                       (reset! updating-compensation? false))}))]
    (r/create-class
     {:reagent-render
      (fn []
        [Form {:on-submit (fn []
                            (let [cents (accounting/string->cents @compensation-amount)]
                              (cond (= cents 0)
                                    (reset! compensation-amount "$0.00")
                                    (> cents 0)
                                    (update-compensation! cents)
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
           "Update"]]])
      :component-will-mount (fn [this]
                              (swap! compensation-amount accounting/cents->string))})))

(defn ProjectCompensation
  "Display a project compensation"
  [compensation]
  (let [project-id @(subscribe [:active-project-id])
        compensation-atom (r/cursor state [:project-compensations (:id compensation)])
        active? (r/cursor compensation-atom [:active])
        updating-compensation? (r/cursor compensation-atom [:updating-compensation?])
        toggle-active (fn [cents]
                        (swap! active? not)
                        (reset! updating-compensation? true)
                        (PUT "/api/toggle-active-project-compensation"
                             {:params {:project-id project-id
                                       :compensation-id (:id compensation)
                                       :active @active?}
                              :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                              :handler (fn [response]
                                         (reset! updating-compensation? false)
                                         (get-compensations! state)
                                         (.log js/console "success"))
                              :error-handler (fn [error]
                                               ($ js/console log (str "[Error] " "update-compensation!"))
                                               (reset! updating-compensation? false))}))
        ;; delete-compensation! (fn []
        ;;                        (DELETE "/api/project-compensation"
        ;;                                {:params {:compensation-id (:id compensation)
        ;;                                          :project-id project-id}
        ;;                                 :headers {"x-csrf-token" @(subscribe [:csrf-token])}
        ;;                                 :handler (fn [response]
        ;;                                            (get-compensations! state))
        ;;                                 :error-handler (fn [error]
        ;;                                                  ($ js/console log (str "[Error] delete-compensation!")))}))
        ]
    #_[:div
     [:div [EditCompensationForm compensation]
      [:div.ui.button {:on-click #(reset! editing? false)} "Dismiss"]]]
    [:div (accounting/cents->string (get-in compensation [:rate :amount])) " per Article"
     [Button {:toggle true
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
                                                 (reset! compensation-amount "$0.00")
                                                 (.log js/console "success"))
                                      :error-handler (fn [error]
                                                       ($ js/console log (str "[Error] " "create-compensation!"))
                                                       (reset! creating-new-compensation? false))}))]
    (r/create-class
     {:reagent-render
      (fn []
        [Form {:on-submit (fn []
                            (.log js/console "Form Submitted")
                            (.log js/console "compensation-amount: " @compensation-amount)
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
        project-compensations (r/cursor state [:project-compensations])
        retrieving-compensations? (r/cursor state [:retrieving-compensations?])
        creating-new-compensation? (r/cursor state [:creating-new-compensation?])]
    (r/create-class
     {:reagent-render
      (fn []
        [:div.ui.segment
         [:h4.ui.dividing.header "Project Compensation"]
         ;; display the current compensations
         (when-not (nil? @project-compensations)
           [:div
            (map
             (fn [compensation]
               ^{:key [:id (:id compensation)]}
               [ProjectCompensation compensation])
             (vals @project-compensations))])
         ;; the form for compensations
         #_(when (< (count @project-compensations)
                  1))
         (if (or @creating-new-compensation?
                 @retrieving-compensations?)
           [:div {:class "ui active centered inline loader"}]
           [CreateCompensationForm])])
      :component-did-mount (fn [this]
                             (get-compensations! state))})))

(defn CompensationSummary
  []
  (let [amount-owed (r/cursor state [:amount-owed])
        all-project-user-ids @(subscribe [:project/member-user-ids nil true])]
    (amount-owed! state "2018-9-1" "2018-9-30")
    (when-not (empty? (keys @amount-owed))
      [:div.ui.segment
       [:h4.ui.dividing.header "Compensation Summary"]
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
                       labels (map :name compensation-maps)
                       data {:labels labels
                             :datasets [{:data (mapv amount-owed compensation-maps)
                                         :backgroundColor (nth charts/paul-tol-colors (count compensation-maps))}]}
                       font-color (charts/graph-text-color)
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
                   (when (> total-owed 0)
                     ^{:key compensation-id}
                     [:div
                      [:h4 "Total owed at " (accounting/cents->string (:amount rate)) " / " (:item rate) " :    " (accounting/cents->string total-owed)]
                      [chartjs/horizontal-bar
                       {:data data
                        :height (charts/label-count->chart-height (count labels))
                        :options options}]])))
               (keys @amount-owed)))])))
