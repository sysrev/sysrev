(ns sysrev-web.ui.home)


(defn ratings-list [articles]
  (fn [articles]
    [:div.ui.cards
     (let [num (count articles)] 
       (map-indexed
        (fn [idx item]
          (let [article (get-in item [:t :item])]
            [:div.ui.fluid.card
             [:div.content
              [:div.header (:title article)]]
             [:div.content (:abs article)]]))
        articles))]))

(defn home [state]
  (if (contains? @state :articles)
    [ratings-list (:articles @state)]
    [:div]))
