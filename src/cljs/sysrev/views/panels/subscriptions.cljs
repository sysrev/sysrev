(ns sysrev.views.panels.subscriptions
  (:require [reagent.core :as r]))

;; based off of: https://codepen.io/caiosantossp/pen/vNazJy?q=semantic%20ui%20plan%20&order=popularity&depth=everything&show_forks=false

(defn Plan
  "Props:
  {:name <string>   ; 
   :price <integer>
       "
  []
  (fn [{:keys [name price features]}]
    [:div {:class "column"}
     [:div {:class "ui segments plan"}
      [:div {:class "ui top attached segment violet inverted plan-title"}
       [:span {:class "ui header"} "Produto 1"]]
      [:div {:class "ui  attached segment feature"}
       [:div {:class "amount"} "R$ 40,50"]]
      [:div {:class "ui  attached secondary segment feature"}
       [:i.red.times.icon] "  \n                Item 2"]
      [:div {:class "ui  attached segment feature"}
       [:i.red.times.icon] "Item 3"]
      [:div {:class "ui bottom attached violet button btn-plan"}
       [:i.cart.icon] "Selecionar Pacote"]]]))

(defn Plans
  []
  [:div {:class "ui container"}
   [:div {:class "ui three columns stackable grid"}
    [Plan]]])
