(ns sysrev.views.panels.plans
  (:require [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.payments :as payments]
            [sysrev.views.base :refer [panel-content]]))

(def plans (r/atom nil))

(def-data :plans
  :loaded? (nil? @plans)
  :uri (fn [] "/api/plans")
  :prereqs (fn [] [[:identity]])
  :process (fn [{:keys [db]} _ result]
             (.log js/console (clj->js (:plans result)))
             (reset! plans (:plans result))
             {:db db}))

(defn cents->dollars
  "Converts an integer value of cents to dollars"
  [cents]
  (str (-> cents (/ 100) (.toFixed 2))))

;; based on:
;; https://codepen.io/caiosantossp/pen/vNazJy

(defn Plan
  "Props is:
  {:name   <string>
   :amount <integer> ; in USD cents"
  []
  (fn [{:keys [name amount color]
        :or {color "blue"}}]
    [:div {:class "column"}
     [:div {:class "ui segments plan"}
      [:div {:class (str "ui top attached segment inverted plan-title "
                         color)}
       [:span {:class "ui header"} name]]
      [:div {:class "ui attached segment feature"}
       [:div {:class "amount"}
        (if (= 0 amount)
          "Free"
          (str "$" (cents->dollars amount))) ]]
      [:div {:class "ui  attached secondary segment feature"}
       [:i {:class "icon red remove"}] "Item 1" ]
      [:div {:class "ui  attached segment feature"}
       [:i {:class "icon red remove"}] "Item 2" ]
      [:div {:class "ui  attached secondary segment feature"}
       [:i {:class "icon red remove"}] "Item 3" ]
      [:div {:class (str "ui bottom attached button btn-plan "
                         color)}
       [:i {:class "cart icon"}] "Select Plan" ]]]))

(defn Plans
  []
  [:div {:class "ui three columns stackable grid"}
   (when (nil? @plans)
     {:key :loader}
     [:div.ui.small.active.loader])
   (when-not (nil? @plans)
     (doall (map (fn [plan]
                   (.log js/console (clj->js plan))
                   ^{:key (:product plan)}
                   [Plan plan])
                 (sort-by :amount @plans))))])

(defmethod panel-content [:plans] []
  (fn [child]
    (dispatch [:fetch [:plans]])
    [:div.ui.segment
     [Plans]]))
