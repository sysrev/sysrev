(ns sysrev-web.ui.labels
  (:require [sysrev-web.base :refer [server-data]]))

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
      (:criteria @server-data)
      (map
       (fn [[id criteria]]
         ^{:key {:label-entry id}}
         [:tr
          [:td (:name criteria)]
          [:td (:question criteria)]
          [:td (if (true? (:is_inclusion criteria))  "Yes" "No")]]))))]])
