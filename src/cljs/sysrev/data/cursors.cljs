(ns sysrev.data.cursors
  (:require [medley.core :refer [deep-merge]]))

(defn cursor-val->map
  "Given a cursor-val of the form [(kw_1|integer_1) ... <kw_i|integer_i>
  val], return a map representation of the cursor-val"
  [cursor-val]
  (let [[cfirst & crest] cursor-val]
    (cond (= 1 (count cursor-val))   cfirst
          (number? cfirst)           [(cursor-val->map crest)]
          :else                      {cfirst (cursor-val->map crest)})))

(defn prune-cursor
  "Prune a cursor down to its first index.
  e.g. [:foo :bar 0 :baz 1 :quz] -> [:foo :bar]"
  [v & [pruned-cursor]]
  (let [kw (first v)
        pruned-cursor (or pruned-cursor [])]
    (cond (empty? v)    pruned-cursor
          (number? kw)  pruned-cursor
          :else         (prune-cursor (into [] (rest v))
                                      (conj pruned-cursor kw)))))

(defn map-from-cursors
  "Given a coll of cursors and edn map, m, extract the values
  the cursors point to and create a new map that is the combination of those cursors"
  [m coll]
  (let [cursor-vals (map #(conj % (get-in m %)) coll)]
    (into {} (when (and (seq coll) (seq m))
               (->> (map cursor-val->map cursor-vals)
                    (apply deep-merge))))))
