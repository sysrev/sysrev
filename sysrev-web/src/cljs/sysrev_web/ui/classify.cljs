(ns sysrev-web.ui.classify
  (:require [sysrev-web.base :refer [server-data
                                     label-queue
                                     label-queue-head
                                     debug-box
                                     label-skip
                                     label-skipped-head
                                     label-load-skipped]]
            [sysrev-web.routes :refer [label-queue-update send-tags]]
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
  (let [criteria (:criteria @server-data)
        criteria-ids (keys criteria)
        criteria-state (r/atom (zipmap criteria-ids (repeat nil)))]
    (fn [handler]
      (let [make-handler (fn [cid]
                             #(do (swap! criteria-state assoc cid %)
                                  (println (str "change " cid " to " %))
                                  (handler @criteria-state)))]
        [:div.ui.sixteen.wide.column.segment
         [debug-box @criteria-state]
         (doall
           (->>
             criteria
             (map (fn [[cid criterion]]
                    ^{:key (name cid)}
                    [:div.ui.two.column.middle.aligned.grid
                     [:div.left.aligned.column (:questionText criterion)]
                     [:div.right.aligned.column
                      [three-state-selection (make-handler cid) (cid @criteria-state)]]]))))]))))




(defn navigate []
  [:div.ui.buttons
   (when-not (nil? (label-skipped-head))
     [:div.ui.secondary.left.icon.button {:on-click label-load-skipped}
      [:i.left.arrow.icon]
      "Previous"])
   [:div.ui.primary.right.icon.button {:on-click label-skip}
    "Next"
    [:i.right.arrow.icon]]])

(defn classify []
  (fn []
    (label-queue-update)
    (let [adata (label-queue-head)
          article (:article adata)
          score (- 1.0 (Math/abs (:score adata)))
          percent (Math/round (* 100 score))
          aid (:article_id adata)
          criteria-change-handler (fn [st] (send-tags aid st))]
      [:div.ui.grid.container
       [:h2 "Article data"]
       [similarity-card article nil score percent aid]
       [:div.two.column.row
        [:div.column
         [:h2 "Alter labels"]]
        [:div.right.aligned.column
         [navigate]]]
       [criteria-block criteria-change-handler]])))
