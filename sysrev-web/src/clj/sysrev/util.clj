(ns sysrev.util)

(defn map-values
  "Map a function over the values of a collection of pairs (vector of vectors,
  hash-map, etc.) Optionally accept a result collection to put values into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([f m]
   (map-values f {} m)))

(defn parse-number
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (when (re-find #"^-?\d+\.?\d*$" s)
    (read-string s)))

(defn in?
  "Tests if `coll` contains an element equal to `x`.
  With one argument `coll`, returns the function #(in? coll %)."
  ([coll x] (some #(= x %) coll))
  ([coll] #(in? coll %)))

(defn mapify-by-id
  "Convert the sequence `entries` to a map, using the value under `id-key` from
  each entry as its map key.
  If `remove-key?` is true, `id-key` will also be dissoc'd from each entry."
  [id-key remove-key? entries]
  (->> entries
       (mapv #(let [k (get % id-key)
                    m (if remove-key?
                        (dissoc % id-key)
                        %)]
                [k m]))
       (apply concat)
       (apply hash-map)))

(defn mapify-group-by-id
  "Convert the sequence `entries` to a map, using the value under `id-key` from
  each entry as its map key, with each value of the result being a vector
  of the entries sharing the key value.
  If `remove-key?` is true, `id-key` will also be dissoc'd from each entry."
  [id-key remove-key? entries]
  (let [all-keys (->> entries (map #(get % id-key)) distinct)]
    (->> all-keys
         (mapv (fn [key]
                 [key
                  (->> entries
                       (filter #(= (get % id-key) key))
                       (mapv #(if remove-key?
                                (dissoc % id-key)
                                %)))]))
         (apply concat)
         (apply hash-map))))
