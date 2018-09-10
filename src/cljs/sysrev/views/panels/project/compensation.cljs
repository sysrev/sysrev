(ns sysrev.views.panels.project.compensation
  (:require [reagent.core :as r]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.views.semantic :refer [Form FormGroup FormInput]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:project :project :compensation])

;; set this to defonce when done developing
(def state (r/cursor app-db [:state :panels panel]))

(defn ProjectCompensation
  []
  (let [support-level (r/cursor state [:support-level])
        user-support-level (r/cursor state [:user-support-level])]
    (reset! user-support-level "$0.00")
    (fn []
      [:div.ui.segment
       [:h4.ui.dividing.header "Project Compensation"]
       [Form {:on-submit (fn []
                           (.log js/console "Form Submitted")
                           (.log js/console "user-support-level: " @user-support-level)
                           (let [cents (accounting/string->cents @user-support-level)]
                             (cond (= cents 0)
                                   (reset! user-support-level "$0.00")
                                   (> cents 0)
                                   (do ;;(reset! loading? true)
                                     ;;(dispatch [:action [:support/support-plan cents]])
                                     (reset! user-support-level (accounting/cents->string cents))
                                     (.log js/console "> cents 0: " cents)
                                     )
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
         "Submit"]]])))

