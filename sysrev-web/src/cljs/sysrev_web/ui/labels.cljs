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
      (d/project-labels-ordered)
      (map
       (fn [{:keys [label-id name question definition]}]
         (let [[inclusion-value] (-> definition :inclusion-values)
               style {:margin-top "-4px"
                      :margin-bottom "-4px"}]
           ^{:key {:label-entry label-id}}
           [:tr
            [:td name]
            [:td question]
            [:td
             (cond (true? inclusion-value)
                   [true-false-nil-tag
                    "large" style true "Yes" true]
                   (false? inclusion-value)
                   [true-false-nil-tag
                    "large" style true "No" false]
                   :else
                   [true-false-nil-tag
                    "large" style false
                    "Extra label" nil])]])))))]])
