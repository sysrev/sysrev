(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.ajax :refer [get-article
                                     get-ranking-article-ids
                                     get-ui-filtered-article-ids
                                     get-classified-ids]]
            [cljs.pprint :as pprint :refer [cl-format]]
            [sysrev-web.ui.base :refer [out-link]]
            [sysrev-web.react.components :refer [link]]
            [sysrev-web.routes :as routes]
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
          (fn [{cid :id answer :answer}]
            (let [ck (-> cid str keyword)
                  criteria-name
                    (get-in @server-data [:criteria ck :name])
                  answer-str (case answer
                               nil "unknown"
                               (str answer))]
              [:div.item {:key cid}
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
    (let [page-article-ids (get-ui-filtered-article-ids)]
      [:div
       [:div.ui.segment (str "Showing " (count page-article-ids) " articles")]
       [:div.ui.cards
        (doall
          (->>
            page-article-ids
            (map
             (fn [article-id]
              (let [article (get-article article-id)
                    score (- 1.0 (Math/abs (:score article)))
                    percent (Math/round (* 100 score))
                    criteria (-> @server-data :articles-criteria article-id)]
                ^{:key article-id}
                [similarity-card article criteria score percent article-id])))))]])))


(defn filter-search []
  (let [handler #(swap! state assoc :filter-text (.. % -target -value))]
    [:div.ui.fluid.input
     [:input {:value (:filter-text @state) :on-change handler}]
     [:div.ui.primary.button "Search"]]))

(defn filter-list []
  (fn []
    (let [criteria (:criteria @server-data)
          handler #(swap! state update-in [:article-filter %] not)
          boxes
            (map
              (fn [[id item]] (assoc item :handler #(handler id) :id (str id)))
              criteria)
          checked?
            (fn [id]
              (get-in @state [:article-filter id]))]
      [:div.ui.form.list
        (doall
          (map
            (fn [item]
              (let [id (:id item)]
                [:div.ui.toggle.checkbox.item {:key (:id item)}
                 [:input {:checked
                          (checked? (:id item))
                          :id (:id item)
                          :type "checkbox"
                          :on-change (:handler item)}]
                 [:label {:style {:cursor "pointer"} :for (:id item)} (:name item)]]))
            boxes))])))


(defn home []
  (fn []
    (let [page-num (:ranking-page @state)]
      (if page-num
        [:div.ui.container
         [link routes/user "Go to /user"]
         [:div.ui.segment
          [debug-box @state]
          ;;[debug-box "article ids" (get-ui-filtered-article-ids)]
          [filter-search]
          [filter-list]
          [ratings-list page-num]]]))))
