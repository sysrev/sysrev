(ns sysrev-web.ui.classify
  (:require
   [sysrev-web.base :refer [state server-data current-user-id]]
   [sysrev-web.ajax :refer [send-tags pull-article-labels]]
   [sysrev-web.ui.components :refer [three-state-selection with-tooltip]]
   [sysrev-web.ui.article :refer [article-info-component]]
   [sysrev-web.classify :refer
    [label-queue label-queue-head label-skipped-head
     label-load-skipped label-skip label-queue-update]]))

(defn classify-page []
  (when (empty? (label-queue))
    (label-queue-update))
  (let [article-id (label-queue-head)
        criteria (:criteria @server-data)
        criteria-ids (keys criteria)]
    [:div.ui.grid
     [article-info-component article-id false]
     [:div.ui.sixteen.wide.column
      [:h3.ui.top.attached.header.segment
       "Edit labels"]
      [:div.ui.bottom.attached.segment
       [:div.ui.four.column.grid
        (doall
         (->>
          criteria
          (map
           (fn [[cid criterion]]
             ^{:key {:article-label cid}}
             [with-tooltip
              [:div.ui.column
               {:data-content (:question criterion)
                :data-position "top left"
                :style (if (= (:name criterion) "overall include")
                         {:border "1px solid rgba(210,210,210,1)"
                          }
                         {})}
               [:div.ui.two.column.middle.aligned.grid
                [:div.right.aligned.column
                 {:style {:padding-left "0px"}}
                 (str (:short_label criterion) "?")]
                [:div.left.aligned.column
                 {:style {:padding-left "0px"}}
                 [three-state-selection
                  (fn [new-value]
                    (swap! state assoc-in
                           [:page :classify :label-values cid] new-value)
                    (->> (get-in @state [:page :classify :label-values])
                         (send-tags article-id)))
                  (get-in @state [:page :classify :label-values cid])]]]]]))))]]]
     [:div.ui.sixteen.wide.column.center.aligned
      [:div.ui.buttons
       (when-not (nil? (label-skipped-head))
         [:div.ui.secondary.left.icon.button
          {:on-click label-load-skipped}
          [:i.left.arrow.icon]
          "Previous"])
       [:div.ui.primary.right.icon.button
        {:on-click label-skip}
        "Next"
        [:i.right.arrow.icon]]]]]))
