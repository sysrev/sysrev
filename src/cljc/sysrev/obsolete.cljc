;;;
;;; Not used, keeping in case needed later
;;;

(ns sysrev.obsolete)

#_
(defn ^:unused integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (and (keyword? k)
                                  (re-matches #"^\d+$" (name k))
                                  (sutil/parse-number (name k)))
                       k-new (if (integer? k-int) k-int k)
                       v-new (if (map? v) ; convert sub-maps recursively
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))
#_
(defn ^:unused uuidify-map-keys
  "Maps parsed from JSON with UUID keys will have the UUID values changed
  to keywords. This converts any UUID keywords back to UUID values, operating
  recursively through nested maps."
  [m]
  (if-not (map? m)
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-uuid (and (keyword? k)
                                   (->> (name k)
                                        (re-matches
                                         #"^[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+$"))
                                   (to-uuid (name k)))
                       k-new (if (uuid? k-uuid) k-uuid k)
                       v-new (if (map? v) ; convert sub-maps recursively
                               (uuidify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))
