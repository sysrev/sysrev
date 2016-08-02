(ns sysrev-web.ui.labels
  (:require [sysrev-web.base :refer [server-data]]))

(defn labels []
  [:table.ui.celled.table
   [:thead
    [:tr
     [:th "Name"]
     [:th "Question Text"]
     [:th "Inclusion"]
     [:th "Exclusion"]
     [:th "Number Articles"]]]
   [:tbody
    (doall
      (->>
        (:criteria @server-data)
        (map
          (fn [[id criteria]]
            ^{:key id}
            [:tr
             [:td (:name criteria)]
             [:td (:questionText criteria)]
             [:td (str (:isInclusion criteria))]
             [:td (str (:isExclusion criteria))]
             [:td]]))))]])
    