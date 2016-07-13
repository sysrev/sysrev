(ns sysrev-web.ui.home)


(defn ratings-list [articles]
  (fn [articles]
    [:div.ui.fluid.cards
     (let [num (count articles)] 
       (map-indexed
        (fn [idx item]
          (let [article (:_1 (:t item))]
            [:div.ui.card
             [:div.content
              [:div.heaeder (:title article)]]
             [:div.content (:abstract article)]]))
        articles))]))

(defn home [state]
  (if (contains? @state :articles)
    [ratings-list (:articles @state)]
    [:div]))
