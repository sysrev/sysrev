(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.ajax :refer [get-article get-ranking-article-ids]]
            [cljs.pprint :as pprint :refer [cl-format]]))


(defn similarity-bar [score percent]
  (fn [score percent]
    [:div.ui.blue.progress
     [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
      [:div.progress
       (cl-format nil "~4,2F" score)]]]))

(defn criteria-detail [criteria]
  (fn [criteria]
    [:div.content.ui.segment
     [:div.header
      [:div.ui.horizontal.divided.list
       (doall
        (->>
         criteria
         (map
          (fn [criteria-entry]
            (let [criteria-id (:id criteria-entry)
                  criteria-name
                  (get-in @server-data
                          [:criteria :entries criteria-id :name])
                  answer (:answer criteria-entry)
                  answer-str (case answer
                               true "true"
                               false "false"
                               nil "unknown")]
              [:div.item {:key criteria-id}
               [:div.content
                (str criteria-name ": " answer-str)]])))))]]]))

(defn similarity-card [article criteria score percent article-id]
  (fn [article criteria]
    [:div.ui.fluid.card
     [:div.content
      [:div.ui.grid
       [:div.ui.row
        [:div.ui.two.wide.column.center.aligned.middle.aligned
         [:h4.header "Similarity"]]
        [:div.ui.thirteen.wide.column.center.aligned.middle.aligned
         [similarity-bar score percent]]]]
      (when (not (empty? criteria))
        [criteria-detail criteria])]
     [:div.content
       [:div.header
        (-> article :item :title)]
      [:div.content (-> article :item :abs)]]]))


(defn ratings-list [page-num]
  (fn [page-num]
    [:div.ui.cards.container
     (let [page-article-ids (get-ranking-article-ids page-num)]
       (doall
        (map
         (fn [article-id]
          (let [article (get-article article-id)
                score (- 1.0 (Math/abs (:score article)))
                percent (Math/round (* 100 score))
                criteria (:criteria article)]
            ^{:key article-id}
              [similarity-card article criteria score percent article-id]))
         page-article-ids)))]))

(defn home []
  (let [page-num (:ranking-page @state)]
    (if page-num
      [ratings-list page-num])))

