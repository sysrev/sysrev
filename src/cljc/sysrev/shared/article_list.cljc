(ns sysrev.shared.article-list)

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
