(ns sysrev.shared.article-list
  (:require [sysrev.shared.util :refer [in?]]))

(defn is-resolved? [labels]
  (boolean (some :resolve labels)))
(defn resolved-answer [labels]
  (->> labels (filter :resolve) first))
(defn is-conflict? [labels]
  (and (not (is-resolved? labels))
       (< 1 (count (->> labels (map :inclusion) distinct)))))
(defn is-single? [labels]
  (= 1 (count labels)))
(defn is-consistent? [labels]
  (and (not (is-single? labels))
       (not (is-resolved? labels))
       (not (is-conflict? labels))))

(defn article-included? [article-labels overall-id allow-single?]
  (let [entries (get-in article-labels [:labels overall-id])
        resolved? (is-resolved? entries)
        entries (cond->> entries
                  resolved? (filter #(true? (:resolve %))))]
    (and (or (is-resolved? entries)
             (and allow-single? (is-single? entries))
             (is-consistent? entries))
         (in? (map :answer entries) true))))

(defn article-label-value-present? [article-labels label-id value]
  (some (fn [{:keys [answer]}]
          (or (= answer value)
              (and (sequential? answer)
                   (in? answer value))))
        (get-in article-labels [:labels label-id])))
