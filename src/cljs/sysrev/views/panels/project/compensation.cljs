(ns sysrev.views.panels.project.compensation
  (:require [ajax.core :refer [POST GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.views.semantic :refer [Form FormGroup FormInput]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:project :project :compensation])

;; set this to defonce when done developing
(def state (r/cursor app-db [:state :panels panel]))

(defn ProjectCompensation
  []
  (let [project-id @(subscribe [:active-project-id])
        project-compensations (r/cursor state [:project-compensations])
        support-level (r/cursor state [:support-level])
        user-support-level (r/cursor state [:user-support-level])
        retrieving-compensations? (r/cursor state [:retrieving-compensations?])
        retrieving-new-compensation? (r/cursor state [:retrieving-new-compensation?])
        get-compensations! (fn []
                             (reset! retrieving-compensations? true)
                             (GET "/api/project-compensations"
                                  {:params {:project-id project-id}
                                   :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                   :handler (fn [response]
                                              (reset! retrieving-compensations? false)
                                              (reset! project-compensations (get-in response [:result :compensations])))
                                   :error-handler (fn [error-response]
                                                    (reset! retrieving-compensations? false)
                                                    ($ js/console log "[Error] retrieving for project-id: " project-id))}))
        create-compensation! (fn [cents]
                               (reset! retrieving-new-compensation? true)
                               (POST "/api/project-compensation"
                                     {:params {:project-id project-id
                                               :rate {:item "article"
                                                      :amount cents}}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [response]
                                                 (reset! retrieving-new-compensation? false)
                                                 (get-compensations!)
                                                 (reset! user-support-level "$0.00")
                                                 (.log js/console "success"))
                                      :error-handler (fn [error]
                                                       ($ js/console log (str "[Error] " "create-compensation!"))
                                                       (reset! retrieving-new-compensation? false))}))]

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
               [:div (accounting/cents->string (get-in compensation [:rate :amount])) " per Article"])
             @project-compensations)])
         ;; the form for compensations
         (if (or @retrieving-new-compensation?
                 @retrieving-compensations?)
           [:div {:class "ui active centered inline loader"}]
           [Form {:on-submit (fn []
                               (.log js/console "Form Submitted")
                               (.log js/console "user-support-level: " @user-support-level)
                               (let [cents (accounting/string->cents @user-support-level)]
                                 (cond (= cents 0)
                                       (reset! user-support-level "$0.00")
                                       (> cents 0)
                                       (do ;;(reset! loading? true)
                                         ;;(dispatch [:action [:support/support-plan cents]])
                                         ;;(reset! user-support-level (accounting/cents->string cents))
                                         (create-compensation! cents)
                                         (.log js/console "> cents 0: " cents))
                                       :else
                                       (do
                                         ;;(reset! loading? true)
                                         ;;(dispatch [:action [:support/support-plan @support-level]])
                                         (reset! user-support-level (accounting/cents->string cents))
                                         (.log js/console "else: " @support-level)))))}
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
                                  (reset! user-support-level new-value)))]
                [FormInput {:value @user-support-level
                            :on-change on-change}])]
             [:div {:style {:display "inline-block"
                            :margin-left "1em"}} "per Article"]]
            [:br]
            [:button {:class (str "ui button primary")}
             "Submit"]])])
      :component-did-mount (fn [this]
                             (get-compensations!))
      :component-will-mount (fn [this]
                              (reset! user-support-level "$0.00"))})))

