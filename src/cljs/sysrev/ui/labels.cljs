(ns sysrev.ui.labels
  (:require [sysrev.base :refer [state]]
            [sysrev.ui.components :refer [true-false-nil-tag]]
            [sysrev.state.data :as d]))

(defn labels-page []
  [:table.ui.celled.unstackable.table
   [:thead
    [:tr
     [:th {:style {:width "16%"}} "Name"]
     [:th {:style {:width "49%"}} "Description"]
     [:th.center.aligned "Values allowed for inclusion"]]]
   [:tbody
    (doall
     (->>
      (d/project-labels-ordered)
      (map
       (fn [{:keys [label-id name question definition]}]
         (let [inclusion-values (-> definition :inclusion-values)
               group-style {:margin-top "-5px"
                            :margin-bottom "-5px"}
               label-style {:margin-top "3px"
                            :margin-bottom "3px"}]
           ^{:key {:label-entry label-id}}
           [:tr
            [:td name]
            [:td question]
            [:td.center.aligned
             (cond
               (= inclusion-values [true])
               [:div.ui.labels {:style group-style}
                [true-false-nil-tag
                 "large" label-style false "Yes" true false]]
               (= inclusion-values [false])
               [:div.ui.labels {:style group-style}
                [true-false-nil-tag
                 "large" label-style false "No" false false]]
               (empty? inclusion-values)
               [:div.ui.labels {:style group-style}
                [true-false-nil-tag
                 "large" label-style false
                 "Extra label" "basic grey" true]]
               :else
               [:div.ui.labels {:style group-style}
                (for [v inclusion-values]
                  ^{:key {:label-value [label-id v]}}
                  [true-false-nil-tag
                   "large" label-style false (str v) nil true])])]])))))]])
