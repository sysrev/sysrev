(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ajax :refer [get-article get-ranking-article-ids]]))

(defn ratings-list [page-num]
  (fn [page-num]
    [:div.ui.cards
     (let [page-article-ids (get-ranking-article-ids page-num)]
       (doall
        (map
         (fn [article-id]
           (let [article (get-article article-id)]
             ^{:key {:rating-card-id article-id}}
             [:div.ui.fluid.card
              [:div.content
               [:div.header (-> article :item :title)]]
              [:div.content (-> article :item :abs)]]))
         page-article-ids)))]))

(defn home []
  (let [page-num (:ranking-page @state)]
    (if page-num
      [ratings-list page-num]
      [:div])))
