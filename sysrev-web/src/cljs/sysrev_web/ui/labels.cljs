(ns sysrev-web.ui.labels
  (:require [sysrev-web.base :refer [state]]))

(defn labels-page []
  [:table.ui.celled.table
   [:thead
    [:tr
     [:th "Name"]
     [:th "Question Text"]
     [:th "Required for inclusion"]]]
   [:tbody
    (doall
     (->>
      (-> @state :data :criteria)
      (map
       (fn [[id criteria]]
         ^{:key {:label-entry id}}
         [:tr
          [:td (:name criteria)]
          [:td (:question criteria)]
          [:td (if (true? (:is_inclusion criteria))  "Yes" "No")]]))))]])
