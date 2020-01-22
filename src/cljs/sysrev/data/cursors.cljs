(ns sysrev.data.cursors
  (:require [medley.core :refer [deep-merge]]))

(defn cursor-val->map
  "Given a cursor-val of the form [(kw_1|integer_1) ... <kw_i|integer_i> val], return a map representation of the cursor-val"
  [cursor-val]
  (cond (= (count cursor-val) 1)
        (first cursor-val)
        (number? (first cursor-val))
        (vector (cursor-val->map (rest cursor-val)))
        :else
        (hash-map (first cursor-val) (cursor-val->map (rest cursor-val)))))

(defn prune-cursor
  "Prune a cursor down to its first index.
  e.g. [:foo :bar 0 :baz 1 :quz] -> [:foo :bar]"
  [v & [pruned-cursor]]
  (let [kw (first v)
        pruned-cursor (or pruned-cursor [])]
    (cond (not (seq v))
          pruned-cursor
          (number? kw)
          pruned-cursor
          :else
          (prune-cursor (into []
                              (rest v))
                        (conj pruned-cursor kw)))))
(defn map-from-cursors
  "Given a coll of cursors and edn map, m, extract the values
  the cursors point to and create a new map that is the combination of those cursors"
  [m coll]
  (let [cursor-vals (map #(conj % (get-in m %)) coll)]
    (if (and (seq coll)
             (seq m))
      (->> (map cursor-val->map cursor-vals)
           (apply deep-merge))
      {})))
