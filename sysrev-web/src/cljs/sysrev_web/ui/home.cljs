(ns sysrev-web.ui.home
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.routes :as routes :refer [get-article
                                                  get-ranking-article-ids
                                                  get-ui-filtered-article-ids]]
            [cljs.pprint :as pprint :refer [cl-format]]
            [sysrev-web.ui.base :refer [out-link]]
            [sysrev-web.react.components :refer [link]]
            [reagent.core :as r]))


(defn similarity-bar [score percent]
  (fn [score percent]
    [:div.ui.tiny.blue.progress
     [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
      [:div.progress]]]))

(defn criteria-detail [criteria article-id]
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
              [:div.item {:key (str cid "_" article-id)}
               [:div.content
                (str criteria-name ": " answer-str)]])))))]]]))


(defn similarity-card
  "Shows an article with a representation of its match quality and how it
  has been manually classified"
  [article criteria score percent article-id]
  (fn [article criteria score]
    [:div.ui.fluid.card
     [:div.content
       (when-not (nil? score)
         [similarity-bar score percent])
       (when-not (empty? criteria)
        [criteria-detail criteria article-id])]
     [:div.content
      [:div.header (:title article)]
      [:div.content (:abs article)]
      [:div.content.ui.list
       (map-indexed
         (fn [idx url]
           ^{:key idx}
           [out-link url])
         (-> article :urls))]]]))


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
              (let [adata (get-article article-id)
                    article (:item adata)
                    score (- 1.0 (Math/abs (:score adata)))
                    percent (Math/round (* 100 score))
                    criteria (-> @server-data :articles-criteria article-id)]
                ^{:key article-id}
                [similarity-card article criteria score percent article-id])))))]])))


(defn filter-search []
  (let [handler #(swap! state assoc :filter-text (.. % -target -value))]
    [:div.ui.fluid.input
     [:input {:value (:filter-text @state) :on-change handler}]
     [:div.ui.primary.button "Search"]]))

(defn filter-bool [{:keys [class]} item handler checked]
  (fn [{:keys [class]} item handler checked]
    [:div.ui.checkbox.item {:class class}
     [:input {:key "input"
              :checked checked
              :id (:id item)
              :type "checkbox"
              :on-change handler}]
     [:label {:key "label" :style {:cursor "pointer"} :for (:id item)} (:name item)]]))

(defn filter-check [item handler checked]
  [filter-bool {} item handler checked])

(defn filter-slider [item handler checked]
  [filter-bool {:class "toggle"} item handler checked])

(defn filter-list []
  (let [criteria (:criteria @server-data)
        make-handler (fn [key] #(swap! state update-in [:article-filter key] not))
        unclassified-handler #((swap! state update-in [:article-filter :unclassified-only] not)
                               (when (get-in @state [:article-filter :unclassified-only])
                                     (swap! state assoc-in [:article-filter :classified-only] false)))
        classified-handler #((swap! state update-in [:article-filter :classified-only] not)
                             (when (get-in @state [:article-filter :classified-only])
                                   (swap! state assoc-in [:article-filter :unclassified-only] false)))
        filter-set? (fn [key] (get-in @state [:article-filter key]))
        boxes (->> criteria
                (map
                  (fn [[id item]] (assoc item :id id))))]
    [:div.ui.form.list
     [filter-check {:name "Classified only" :id "classified"} classified-handler (filter-set? :classified-only)]
     [filter-check {:name "Unclassified only" :id "unclassified"} unclassified-handler (filter-set? :unclassified-only)]
     (doall
       (->> boxes
         (map
           (fn [item]
             (let [id (:id item)]
               ^{:key id}
               [filter-slider item (make-handler id) (filter-set? id)])))))]))


(defn home []
  (fn []
    (let [page-num (:ranking-page @state)]
      (if page-num
        [:div.ui.container
         [:div.ui.segment
          [filter-search]
          [filter-list]
          [ratings-list page-num]]]))))
