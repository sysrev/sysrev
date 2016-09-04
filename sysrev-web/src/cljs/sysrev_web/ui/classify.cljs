(ns sysrev-web.ui.classify
  (:require
   [sysrev-web.base :refer [state server-data current-user-id]]
   [sysrev-web.ajax :refer [send-tags pull-article-labels]]
   [sysrev-web.ui.components :refer [three-state-selection]]
   [sysrev-web.ui.article :refer [article-info-component]]
   [sysrev-web.classify :refer
    [label-queue label-queue-head label-skipped-head
     label-load-skipped label-skip label-queue-update]]))

(defn article-labels-form
  "UI component for modifying the label values of the active article.
  `on-change` will be called on each value change with the updated
  map of label values."
  [on-change]
  (fn [on-change]
    (let [criteria (:criteria @server-data)
          criteria-ids (keys criteria)]
      [:div.ui.sixteen.wide.column.segment
       (doall
        (->>
         criteria
         (map
          (fn [[cid criterion]]
            ^{:key {:article-label cid}}
            [:div.ui.two.column.middle.aligned.grid
             [:div.left.aligned.column (:question criterion)]
             [:div.right.aligned.column
              [three-state-selection
               (fn [new-value]
                 (swap! state assoc-in
                        [:page :classify :label-values cid] new-value)
                 (on-change (get-in @state [:page :classify :label-values])))
               (get-in @state [:page :classify :label-values cid])]]]))))])))

(defn classify-navigate-buttons []
  [:div.ui.buttons
   (when-not (nil? (label-skipped-head))
     [:div.ui.secondary.left.icon.button
      {:on-click label-load-skipped}
      [:i.left.arrow.icon]
      "Previous"])
   [:div.ui.primary.right.icon.button
    {:on-click label-skip}
    "Next"
    [:i.right.arrow.icon]]])

(defn classify-page []
  (fn []
    (when (empty? (label-queue))
      (label-queue-update))
    (let [article-id (label-queue-head)]
      [:div.ui.grid.container
       {:style {:padding-bottom "24px"}}
       [:h2 "Article data"]
       [article-info-component article-id false]
       [:div.two.column.row
        [:div.column
         [:h2 "Alter labels"]]
        [:div.right.aligned.column
         [classify-navigate-buttons]]]
       [article-labels-form #(send-tags article-id %)]])))
