(ns sysrev-web.ui.classify
  (:require [sysrev-web.base :refer [state
                                     server-data
                                     label-queue
                                     label-queue-head
                                     debug-box
                                     label-skip
                                     label-skipped-head
                                     label-load-skipped
                                     label-queue]]
            [sysrev-web.routes :refer [label-queue-update send-tags overall-include-id
                                       pull-article-labels]]
            [sysrev-web.ui.home :refer [similarity-card]]
            [reagent.core :as r]))

(defn radio-button [handler set child]
  (let [class (when set "primary")]
    [:div.ui.icon.button {:class class :on-click handler} child]))

(defn three-state-selection [change-handler curval]
  ;; nil for unset, true, false
  (fn [change-handler curval]
    [:div.ui.large.buttons
     [radio-button #(change-handler false) (false? curval) "No"]
     [radio-button #(change-handler nil) (nil? curval) "?"]
     [radio-button #(change-handler true) (true? curval) "Yes"]]))

(defn criteria-block [handler]
  (fn [handler]
    (when-let [criteria (:criteria @server-data)]
      (let [overall-id (overall-include-id)
            criteria-ids (->> criteria keys (sort-by #(js/parseInt (name %)))
                              (remove (partial = overall-id)))
            make-handler (fn [cid]
                           #(do (swap! state assoc-in [:criteria cid] %)
                                (handler (:criteria @state))))]
        [:div
         [:div.ui.sixteen.wide.column.segment
          (doall
           (->>
            criteria-ids
            (map (fn [cid]
                   (let [criterion (get criteria cid)]
                     ^{:key (name cid)}
                     [:div.ui.two.column.middle.aligned.grid
                      [:div.left.aligned.column (:questionText criterion)]
                      [:div.right.aligned.column
                       [three-state-selection
                        (make-handler cid)
                        (get-in @state [:criteria cid])]]])))))]
         (let [cid overall-id
               criterion (get criteria overall-id)]
           ^{:key (name cid)}
           [:div.ui.sixteen.wide.column.segment
            [:div.ui.two.column.middle.aligned.grid
             [:div.left.aligned.column (:questionText criterion)]
             [:div.right.aligned.column
              [three-state-selection
               (make-handler cid)
               (get-in @state [:criteria cid])]]]])]))))

(defn update-active-criteria []
  (let [aid (:article_id (label-queue-head))
        uid (-> @server-data :user :id str keyword)]
    (pull-article-labels
     aid
     (fn [response]
       (let [result (-> response :result uid)]
         (swap! state assoc :criteria result))))))

(defn navigate []
  [:div.ui.buttons
   (when-not (nil? (label-skipped-head))
     [:div.ui.secondary.left.icon.button
      {:on-click (fn []
                   (label-load-skipped)
                   (update-active-criteria))}
      [:i.left.arrow.icon]
      "Previous"])
   [:div.ui.primary.right.icon.button
    {:on-click (fn []
                 (label-skip)
                 (update-active-criteria))}
    "Next"
    [:i.right.arrow.icon]]])

(defn classify []
  (fn []
    (when (empty? (label-queue)) (label-queue-update))
    (let [adata (label-queue-head)
          article (:article adata)
          score (- 1.0 (Math/abs (:score adata)))
          percent (Math/round (* 100 score))
          aid (:article_id adata)
          criteria-change-handler (fn [st] (send-tags aid st))]
      [:div.ui.grid.container
       {:style {:padding-bottom "24px"}}
       [:h2 "Article data"]
       [similarity-card article nil score percent aid]
       [:div.two.column.row
        [:div.column
         [:h2 "Alter labels"]]
        [:div.right.aligned.column
         [navigate]]]
       [criteria-block criteria-change-handler]])))

(add-watch
 state :label-queue-update
 (fn [k v old new]
   (let [q-old (-> old :label-activity)
         q-new (-> new :label-activity)]
     (when (and (not= q-old q-new)
                (< (count q-new) 5))
       (label-queue-update)))))
