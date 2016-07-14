(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ajax :refer [get-article get-ranking-article-ids]]
            [cljs.pprint :as pprint]))

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
               [:div.header
                [:div.ui.horizontal.divided.list
                 [:div.item
                  [:div.content
                   "Distance: "
                   (->> article :score Math/abs (pprint/cl-format nil "~5,3F"))]]
                 (let [criteria (:criteria article)]
                   (if (nil? criteria)
                     [:div.item
                      [:div.content
                       "Criteria values unknown"]]
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
                            ^{:key {:article-criteria [article-id criteria-id]}}
                            [:div.item
                             [:div.content
                              (str criteria-name ": " answer-str)]])))))))]]]
              [:div.content
               [:div.header (-> article :item :title)]]
              [:div.content (-> article :item :abs)]]))
         page-article-ids)))]))

(defn home []
  (let [page-num (:ranking-page @state)]
    (if page-num
      [ratings-list page-num]
      [:div])))
