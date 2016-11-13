(ns sysrev-web.ui.labels
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.ui.components :refer [true-false-nil-tag]]
            [sysrev-web.state.data :as d]))

(defn labels-page []
  [:table.ui.celled.unstackable.table
   [:thead
    [:tr
     [:th "Name"]
     [:th "Question Text"]
     [:th "Required value for inclusion"]]]
   [:tbody
    (doall
     (->>
      (d/project :criteria)
      (map
       (fn [[id criteria]]
         ^{:key {:label-entry id}}
         [:tr
          [:td (:name criteria)]
          [:td (:question criteria)]
          [:td
           (let [style {:margin-top "-4px"
                        :margin-bottom "-4px"}]
             (cond (true? (:is-inclusion criteria))
                   [true-false-nil-tag
                    "large" style true "Yes" true]
                   (false? (:is-inclusion criteria))
                   [true-false-nil-tag
                    "large" style true "No" false]
                   :else
                   [true-false-nil-tag
                    "large" style false
                    "Extra label" nil]))]]))))]])
