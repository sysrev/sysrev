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
     [:th "Values allowed for inclusion"]]]
   [:tbody
    (doall
     (->>
      (d/project-labels-ordered)
      (map
       (fn [{:keys [label-id name question definition]}]
         (let [inclusion-values (-> definition :inclusion-values)
               style {:margin-top "-4px"
                      :margin-bottom "-4px"}]
           ^{:key {:label-entry label-id}}
           [:tr
            [:td name]
            [:td question]
            [:td
             (cond (= inclusion-values [true])
                   [true-false-nil-tag
                    "large" style true "Yes" true]
                   (= inclusion-values [false])
                   [true-false-nil-tag
                    "large" style true "No" false]
                   (empty? inclusion-values)
                   [true-false-nil-tag
                    "large" style false
                    "[Extra]" nil]
                   :else
                   [:div
                    (doall
                     (->>
                      inclusion-values
                      (map
                       (fn [v]
                         ^{:key {:label-value [label-id v]}}
                         [true-false-nil-tag
                          "large" style false (str v) nil]))))])]])))))]])
