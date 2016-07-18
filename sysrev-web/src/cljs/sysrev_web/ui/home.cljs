(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.ajax :refer [get-article get-ranking-article-ids]]
            [cljs.pprint :as pprint :refer [cl-format]]
            [clojure.string :refer [split join]]
            [goog.string :refer [unescapeEntities]]))

(def nbsp (unescapeEntities "&nbsp;"))

(defn url-domain' [url]
  (-> url (split "//") second (split "/") first (split ".") (->> (take-last 2) (join "."))))

(defn url-domain [url]
  (let [res (url-domain' url)]
    (if (string? res) res url)))

(defn out-link [url]
  [:a.item {:target "_blank" :href url}
    (url-domain url)
    nbsp
    [:i.external.icon]])

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
                (str criteria-name ": " answer-str)]])))))]]
     [debug-box criteria]]))

(defn similarity-card [article criteria score percent article-id]
  (fn [article criteria]
    [:div.ui.fluid.card
     [:div.content
       [similarity-bar score percent]
       (when (not (empty? criteria))
        [criteria-detail criteria])]
     [:div.content
       [:div.header
        (-> article :item :title)]
      [:div.content (-> article :item :abs)]
      [:div.content.ui.list
       (map-indexed
         (fn [idx url]
           ^{:key idx}
           [out-link url])
         (-> article :item :urls))]
      [debug-box article]]]))

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

