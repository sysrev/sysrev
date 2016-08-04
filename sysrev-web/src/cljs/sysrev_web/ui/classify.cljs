(ns sysrev-web.ui.classify
  (:require [sysrev-web.base :refer [server-data
                                     label-queue
                                     label-queue-head
                                     debug-box
                                     label-skip
                                     label-skipped-head
                                     label-load-skipped]]
            [sysrev-web.routes :refer [label-queue-update]]
            [sysrev-web.ui.home :refer [similarity-card]]
            [reagent.core :as r]))

(defn radio-button [handler set child]
  (let [class (when set "primary")]
    [:div.ui.icon.button {:class class :on-click handler} child]))


(defn three-state-selection [change-handler]
  ;; nil for unset, true, false
  (let [selection (r/atom nil)
        make-handler (fn [v] #((reset! selection v) (change-handler v)))]
    (fn [change-handler]
      [:div.ui.large.buttons
       [radio-button (make-handler false) (false? @selection) "No"]
       [radio-button (make-handler nil) (nil? @selection) "?"]
       [radio-button (make-handler true) (true? @selection) "Yes"]])))

(defn criteria-block []
  (fn []
    (let [criteria (:criteria @server-data)]
      [:div.ui.sixteen.wide.column.segment
       (doall
         (->>
           criteria
           (map (fn [[id criterion]]
                  ^{:key id}
                  [:div.ui.two.column.middle.aligned.grid
                   [:div.left.aligned.column (:questionText criterion)]
                   [:div.right.aligned.column
                    [three-state-selection]]]))))])))


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
          aid (:articleId adata)]
      [:div.ui.grid.container
       [:h2 "Article data"]
       [similarity-card article nil score percent aid]
       [:div.two.column.row
        [:div.column
         [:h2 "Alter labels"]]
        [:div.right.aligned.column
         [navigate]]]
       [criteria-block]])))
