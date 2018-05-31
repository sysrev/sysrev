(ns sysrev.views.panels.project.support
  (:require [cljsjs.accounting]
            [cljsjs.semantic-ui-react]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.views.base :refer [panel-content]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def panel [:project :project :support])

(def state (r/cursor app-db [:state :panels panel]))

(def semantic-ui js/semanticUIReact)
(def Form (r/adapt-react-class (goog.object/get semantic-ui "Form")))
(def FormButton (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Button)))
(def FormField (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Field)))
(def FormGroup (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Group)))
(def FormInput (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Input)))
(def FormRadio (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Radio)))
(def Label (r/adapt-react-class
            (goog.object/get semantic-ui "Label")))

(def-action :payments/support-plan
  :uri (fn [] "/api/support-project")
  :content (fn [amount]
             {:amount amount})
  :process
  (fn [{:keys [db]} _ {:keys [success] :as result}]
    (.log js/console (clj->js result))
    {}
    ))
;; functions around accounting.js
(defn unformat
  "Converts a string to a currency amount (default is in dollar)"
  [string]
  ($ js/accounting unformat string))

(defn to-fixed
  "Converts a number to a fixed value string to n decimal places"
  [number n]
  ($ js/accounting toFixed number n))

(defn format-money
  "Converts a USD currency string to a number"
  [string]
  ($ js/accounting formatMoney string))

(defn string->cents
  "Convert a string to a number in cents"
  [string]
  (-> (to-fixed string 2)
      unformat
      (* 100)))

(defn Support
  []
  (let [support-level (r/cursor state [:support-level])
        user-support-level (r/cursor state [:user-support-level])]
    [:div.panel
     [:div.ui.segment [:h1 "Support This Project"]
      [Form {:on-submit
             (fn []
               (let [cents (string->cents @user-support-level)]
                 (cond (and (= @support-level
                               :user-defined)
                            (= cents
                               0))
                       (reset! user-support-level "$0.00")
                       (and (= @support-level
                               :user-defined)
                            (> cents
                               0))
                       (do
                         (dispatch [:action [:payments/support-plan cents]])
                         (.log js/console "[Supported at " cents "]"))
                       :else
                       (do (dispatch [:action [:payments/support-plan cents]])
                           (.log js/console "[Supported at " @support-level "]")))))}
       [FormGroup
        [FormRadio {:label "$5 per month"
                    :checked (= @support-level
                                500)
                    :on-change #(reset! support-level 500)}]
        ;;[:p ""]
        ]
       [FormGroup
        [FormRadio {:label "$10 per month"
                    :checked (= @support-level
                                1000)
                    :on-change #(reset! support-level 1000)}]
        ;;[:p "Support this project at $10 per month"]
        ]
       [FormGroup
        [FormRadio {:label "$50 per month"
                    :checked (= @support-level
                                5000)
                    :on-change #(reset! support-level 5000)}]
        ;;[:p "Support this project at $50 per month"]
        ]
       [FormGroup
        [FormRadio {:checked (= @support-level
                                :user-defined)
                    :on-change #(reset! support-level :user-defined)}]
        [:div
         (let [
               on-change (fn [event]
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
                       :on-change on-change
                       :on-click #(reset! support-level :user-defined)}]) " per month"]]
       [FormButton "Continue"]
       ]]]))

(defmethod panel-content panel []
  (fn [child]
    (reset! (r/cursor state [:user-support-level]) "$1.00")
    [Support]))
