(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data]]))

(defn ratings-list [articles]
  (fn [articles]
    [:div.ui.cards
     (let [num (count articles)] 
       (map-indexed
        (fn [idx item]
          (let [article (get-in item [:t :item])]
            ^{:key {:rating-card-idx idx}}
            [:div.ui.fluid.card
             [:div.content
              [:div.header (:title article)]]
             [:div.content (:abs article)]]))
        articles))]))

(defn home []
  (let [page-num (:ranking-page @state)
        articles (and page-num
                      (get-in @server-data [:ranking :pages page-num]))]
    (if articles
      [ratings-list articles]
      [:div])))
