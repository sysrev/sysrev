(ns sysrev.subs.public-labels
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-sub-raw]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.subs.project :refer [active-project-id get-project-raw]]))

(def group-statuses
  [#_ :single :consistent :conflict :resolved])

(defn have-public-labels? [db]
  (let [project-id (active-project-id db)
        project (get-project-raw db project-id)]
    (contains? project :public-labels)))

(reg-sub
 ::public-labels
 :<- [:project/raw]
 (fn [project] (:public-labels project)))

(reg-sub
 ::label-entries
 :<- [::public-labels]
 (fn [all-entries [_ label-id]]
   (->> all-entries
        (map (fn [[article-id article]]
               (let [labels (get-in article [:labels label-id])]
                 (if (not-empty labels)
                   (assoc article
                          :article-id article-id
                          :labels {label-id labels})
                   nil))))
        (remove nil?)
        vec)))

(defn is-resolved? [entries]
  (boolean (some :resolve entries)))
(defn resolved-answer [entries]
  (->> entries (filter :resolve) first))
(defn is-conflict? [entries]
  (and (not (is-resolved? entries))
       (< 1 (count (->> entries (map :inclusion) distinct)))))
(defn is-single? [entries]
  (= 1 (count entries)))
(defn is-consistent? [entries]
  (and (not (is-resolved? entries))
       (not (is-conflict? entries))
       (not (is-single? entries))))

(defn- group-status-filter [status]
  (case status
    :conflict is-conflict?
    :resolved is-resolved?
    :consistent is-consistent?
    :single is-single?
    (constantly true)))

(defn- inclusion-status-filter [status]
  (if (nil? status)
    (constantly true)
    (fn [entries]
      (let [inclusion (->> entries (map :inclusion) distinct)]
        (in? inclusion status)))))

(defn- answer-value-filter [value]
  (if (nil? value)
    (constantly true)
    (fn [entries]
      (let [answers (->> entries
                         (map :answer)
                         (map #(if (sequential? %) % [%]))
                         (apply concat)
                         distinct)]
        (in? answers value)))))

(defn- query-articles-impl
  [label-id entries {:keys [group-status answer-value inclusion-status]
                     :as filters}]
  (let [get-labels #(get-in % [:labels label-id])]
    (->> entries
         (filter (comp (group-status-filter group-status) get-labels))
         (filter (comp (answer-value-filter answer-value) get-labels))
         (filter (comp (inclusion-status-filter inclusion-status) get-labels))
         (sort-by :updated-time >))))

(reg-sub
 :public-labels/query-articles
 (fn [[_ label-id filters]]
   [(subscribe [::label-entries label-id])])
 (fn [[entries] [_ label-id filters]]
   (query-articles-impl label-id entries filters)))
