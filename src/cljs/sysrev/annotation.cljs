(ns sysrev.annotation
  (:require [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data]]))

(def semantic-ui js/semanticUIReact)
(def Popup (r/adapt-react-class (goog.object/get semantic-ui "Popup")))

(def state (r/atom nil))

(defn within?
  "Given a coll of sorted numbers, determine if x is within (inclusive) the range of values"
  [x coll]
  (and (>= x (first coll)) (<= x (last coll))) )

(defn overlap?
  "Given two vectors determine if they overlap"
  [vec1 vec2]
  (or (within? (first vec1) vec2)
      (within? (last vec1) vec2)))

(defn determine-overlaps
  "Given a vector of annotation-indices, assoc the key :overlap true
  to all overlapping indices. Assumes the index maps are in order   "
  ([annotation-indices]
   (if (empty? annotation-indices)
     []
     (let [annotations (->> annotation-indices
                            (filter #(not= (:word %)
                                           nil))
                            (into []))]
       (determine-overlaps [] annotations))))
  ([current-vec rest-vec]
   (cond (= 1 (count rest-vec))
         ;; we're done
         current-vec
         ;; we still have more to compute
         :else
         (let [current-annotation-map (nth rest-vec 0)
               next-annotation-map (nth rest-vec 1)]
           (if (overlap? (:index current-annotation-map)
                         (:index next-annotation-map))
             ;; there is overlap, indicate it
             (determine-overlaps
              ;; the current annotation map overlaps
              (conj current-vec (assoc current-annotation-map
                                       :overlap true))
              ;; the next annotation map overlaps with it
              ;; so this overlap should be indicated
              (into []
                    (cons (assoc next-annotation-map
                                 :overlap true)
                          (nthrest rest-vec 2))))
             ;; there isn't overlap
             (determine-overlaps
              (conj current-vec current-annotation-map)
              (rest rest-vec)))))))

(defn index-length
  "Given an index vector, return its length"
  [index]
  (+ 1 (- (last index) (first index))))

(defn longest-overlaps
  "Given a coll of annotation overlaps, return a coll with the overlaps resolved by taking the largest range"
  ([overlapping-indices]
   (if (empty? overlapping-indices)
     []
     (longest-overlaps [] overlapping-indices {})))
  ([current-vec rest-vec longest-index]
   (let [current-annotation-map (nth rest-vec 0)
         next-annotation-map  (nth rest-vec 1)
         longest (apply max-key #(index-length (:index %))
                        [longest-index current-annotation-map next-annotation-map])
         new-rest-vec (rest rest-vec)]
     (cond
       (= 1 (count new-rest-vec))
       ;; we're done
       (conj current-vec longest)
       ;; there is overlap
       (overlap? (:index current-annotation-map)
                 (:index next-annotation-map))
       (longest-overlaps current-vec new-rest-vec longest)
       ;; there is no overlap, put the longest annotation into current-vec
       :else
       (longest-overlaps (conj current-vec longest)
                         new-rest-vec
                         ;; taken advantage of nil-punning
                         ;; nil = 0
                         {})))))

(defn word-indices
  "Given a string and word, return a map of indices of the form
  {:word <word>
   :indices [[<begin end> ...]]"
  ([string word]
   ;; if the string or word is blank, this results in non-sense,
   ;; return nil
   (if (or (clojure.string/blank? string)
           (clojure.string/blank? word))
     nil
     ;; otherwise, start processing
     (word-indices string word [])))
  ([string word indices]
   (let [offset (or (apply max (flatten indices))
                    0)
         begin-position (clojure.string/index-of (clojure.string/lower-case string) (clojure.string/lower-case word))
         end-position (+ begin-position (count word))
         remaining-string (subs string end-position)]
     (cond
       (clojure.string/blank? remaining-string)
       {:word word :indices indices}
       (nil? begin-position)
       {:word word :indices indices}
       :else
       (word-indices remaining-string word (conj indices [(+ begin-position offset)
                                                                (+ end-position offset)]))))))

(defn word-indices->word-indices-map
  "Given a word-indices map returned by word-indices, create a vector of {:word <word> :index <index>} maps"
  [word-indices]
  (mapv #(hash-map :word (:word word-indices)
                   :index %)
        (:indices word-indices)))

(defn annotation-map->word-indices-maps
  "Given a string and an annotation-map of the form
  {:word <string> :annotation <string>}, return a vector of maps of the form
  [{:word <string> :index [[<begin> <end>]..] :annotation <string>} ...] which are indexed to string"
  [string {:keys [word annotation]}]
  (mapv (partial merge {:annotation annotation})
        (word-indices->word-indices-map (word-indices string word))))

(defn annotations->word-indices-maps
  "Given a string and a vector of annotations maps of the form
  [{:word <string :annotation <string>}..], return a vector of maps for the form
  [{:word <string> :index [[<begin> <end>] ..] :annotation <string>} ...] which are indexed to string"
  [annotations string]
  (->> annotations
       (mapv (partial annotation-map->word-indices-maps string))
       flatten
       (sort-by #(first (:index %)))
       (into [])))

(defn annotations->no-annotations-indices-maps
  "Given a string and vector of indexed annotations, return the indices for which there are no annotations in string"
  [annotations string]
  (let [occupied-chars (sort (flatten (mapv :index 
                                            annotations)))
        no-annotations-indices (merge (mapv #(vector %1 %2)
                                  (take-nth 2 (rest occupied-chars))
                                  (rest (take-nth 2 occupied-chars)))
                            [(last occupied-chars)])]
    (-> (mapv #(hash-map :word nil :annotation nil :index %) no-annotations-indices)
        (merge {:index [0 (first occupied-chars)]
                :annotation nil
                :word nil}))))

(defn annotation-indices
  [annotations string]
  "Given a coll of annotations and a string, return a vector of the form
   [{:word <string> :index [[<begin> <end>] ..] :annotation <string>} ...] for annotations.
  Text which is not to be annotated is denoted with the key-val pair :word nil"
  (->> (annotations->word-indices-maps annotations string)
       flatten
       (sort-by #(first (:index %)))
       (into [])))
;; this corresponds to 'Novel therapies in development for metastatic colorectal cancer'
;; in 'Novel Dose Escalation Methods and Phase I Designs' project
;;(def string @(subscribe [:article/abstract 12184]))

(defn annotation-indices-no-overlap
  [annotations string]
  (let [annotation-indices (annotation-indices annotations string)
        overlapping-indices (->> annotation-indices
                                 determine-overlaps)
        longest-overlapping-annotations (->> overlapping-indices
                                             (filterv :overlap)
                                             longest-overlaps
                                             (mapv #(dissoc % :overlap)))
        non-overlapping-annotations (filterv (comp not :overlap)
                                             overlapping-indices)
        final-annotations (->> (concat longest-overlapping-annotations
                                       non-overlapping-annotations)
                               (sort-by #(first (:index %)))
                               (into []))
        no-annotations-indices (annotations->no-annotations-indices-maps
                                final-annotations
                                string)]
    (->>
     (concat
      final-annotations
      no-annotations-indices)
     (sort-by #(first (:index %)))
     (into []))))

(defn Annotation
  [{:keys [text content color]
    :or {color "#909090"}}]
  (let [highlight-text [:span
                        {:style
                         {:text-decoration (str "underline dotted " color)
                          :cursor (if-not
                                      (clojure.string/blank? content)
                                    "pointer"
                                    "text")}} text]]
    (if-not (clojure.string/blank? content)
      [Popup {:trigger
              (r/as-element
               highlight-text)
              :content content}]
      highlight-text)))

(defn AnnotatedText
  [text annotations]
  (let [annotations (annotation-indices-no-overlap annotations text)]
    [:div
     (map (fn [{:keys [word index annotation]}]
            (let [key (str (gensym word))]
              (if (= word nil)
                ^{:key key}
                (apply (partial subs text) index)
                ^{:key key}
                [Annotation {:text (apply (partial subs text) index)
                             :content annotation}])))
          annotations)]))
