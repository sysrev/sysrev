(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.ajax :refer [get-article get-ranking-article-ids]]
            [cljs.pprint :as pprint :refer [cl-format]]
            [sysrev-web.ui.base :refer [out-link]]
            [reagent.core :as r]))


(defn similarity-bar [score percent]
  (fn [score percent]
    [:div.ui.tiny.blue.progress
     [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
      [:div.progress]]]))

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


(defn similarity-card
  "Shows an article with a representation of its match quality and how it
  has been manually classified"
  [article criteria score percent article-id]
  (fn [article criteria]
    [:div.ui.fluid.card
     [:div.content
       [similarity-bar score percent]
       (when-not (empty? criteria)
        [criteria-detail criteria])]
     [:div.content
      [:div.header (-> article :item :title)]
      [:div.content (-> article :item :abs)]
      [:div.content.ui.list
       (map-indexed
         (fn [idx url]
           ^{:key idx}
           [out-link url])
         (-> article :item :urls))]]]))


(defn ratings-list [page-num]
  (fn [page-num]
    [:div.ui.cards
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

(defn filter-search []
  [:div.ui.fluid.input
   [:input {:onChange #(swap! state assoc :filter-text (-> % .-target .-value))}]
   [:div.ui.primary.button "Search"]])

(defn filter-list []
  (let [criteria (-> @server-data :criteria :entries)
        boxes
          (map
            (fn [[id item]]
              (assoc item
                :handler #(swap! state update-in [:article-filter (keyword (str id))] not)
                :id (str id)))
            criteria)]
    [:div.ui.segment
     [:div.ui.form
      [filter-search]
      [:div.ui.list
       (doall
        (map
          (fn [item]
            [:div.ui.toggle.checkbox.item {key (:id item)}
             [:input {:type "checkbox" :on-change (:handler item)}]
             [:label (:name item)]])
          boxes))]]]))


(defn home []
  (let [page-num (:ranking-page @state)]
    (if page-num
      [:div.ui.container
;;       [debug-box @state]
       [filter-list]
       [ratings-list page-num]])))
