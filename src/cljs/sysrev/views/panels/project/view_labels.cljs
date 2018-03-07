(ns sysrev.views.panels.project.view-labels
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :refer [true-false-nil-tag tabbed-panel-menu]]))

#_ (defmethod panel-content [:project :project :labels] []
     (fn [child]
       [:div.project-content
        (let [active-tab (->> @(subscribe [:active-panel]) (drop 3) first)]
          [tabbed-panel-menu
           [{:tab-id :view
             :content "View"
             :action [:project :project :labels :view]}
            {:tab-id :edit
             :content "Edit"
             :action [:project :project :labels :edit]}]
           active-tab
           "project-labels-menu"])
        child]))

#_ (defmethod panel-content [:project :project :labels :view] []
     (fn [child]
       [:div
        [:table.ui.celled.unstackable.table.project-labels
         [:thead
          [:tr
           [:th {:style {:width "16%"}} "Name"]
           [:th {:style {:width "49%"}} "Description"]
           [:th.center.aligned "Values allowed for inclusion"]]]
         [:tbody
          (doall
           (->>
            @(subscribe [:project/label-ids])
            (map
             (fn [label-id]
               (let [name @(subscribe [:label/name label-id])
                     question @(subscribe [:label/question label-id])
                     inclusion-values @(subscribe [:label/inclusion-values
                                                   label-id])
                     group-style {:margin-top "-5px"
                                  :margin-bottom "-5px"}
                     label-style {:margin-top "3px"
                                  :margin-bottom "3px"}]
                 ^{:key label-id}
                 [:tr
                  [:td name]
                  [:td question]
                  [:td.center.aligned
                   (cond
                     (= inclusion-values [true])
                     [:div.ui.labels {:style group-style}
                      [true-false-nil-tag
                       "Yes" true
                       :show-icon? false
                       :color? false
                       :style label-style]]
                     (= inclusion-values [false])
                     [:div.ui.labels {:style group-style}
                      [true-false-nil-tag
                       "No" false
                       :show-icon? false
                       :color? false
                       :style label-style]]
                     (empty? inclusion-values)
                     [:div.ui.labels {:style group-style}
                      [true-false-nil-tag
                       "Extra label" "basic grey"
                       :show-icon? false
                       :color? true
                       :style label-style]]
                     :else
                     [:div.ui.labels {:style group-style}
                      (for [v inclusion-values]
                        ^{:key [label-id v]}
                        [true-false-nil-tag
                         (str v) nil
                         :show-icon? false
                         :color? true
                         :style label-style])])]])))))]]]))
