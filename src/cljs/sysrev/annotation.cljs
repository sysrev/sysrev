(ns sysrev.annotation
  (:require [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-sub-raw reg-event-db
              reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.loading :as loading]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.ui :as ui-state]
            [sysrev.views.components :as ui]
            [sysrev.util :as util :refer [vector->hash-map]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def semantic-ui js/semanticUIReact)
(def Popup (r/adapt-react-class (goog.object/get semantic-ui "Popup")))
(def Dropdown (r/adapt-react-class (goog.object/get semantic-ui "Dropdown")))
;; accessing state for testing:
;; @(r/cursor sysrev.views.article/state [:annotations 7978]))
;; @(subscribe [:article/abstract 7978])

(defn within?
  "Given a coll of sorted numbers, determine if x is within (inclusive) the range of values"
  [x coll]
  (and (>= x (first coll)) (<= x (last coll))) )

(defn overlap?
  "Given two vectors determine if they overlap"
  [vec1 vec2]
  (or (within? (first vec1) vec2)
      (within? (last vec1) vec2)
      (within? (first vec2) vec1)
      (within? (first vec2) vec1)))

(defn index-length
  "Given an index vector, return its length"
  [index]
  (+ 1 (- (last index) (first index))))

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
     (word-indices string word [] 0)))
  ([string word indices offset]
   (let [begin-position (clojure.string/index-of
                         (clojure.string/lower-case string)
                         (clojure.string/lower-case word))
         end-position (+ begin-position (count word))
         remaining-string (subs string end-position)
         new-index [(+ begin-position offset)
                    (+ end-position offset)]]
     (cond
;;; we're done
       (clojure.string/blank? remaining-string)
       {:word word
        ;; if we are at the end of the string and have a capture, put
        ;; it in indices
        :indices
        (if (nil? begin-position)
          indices
          (conj indices new-index))}
       (nil? begin-position)
       {:word word :indices indices}
;;; processing
       ;; at the beginning of the string with a match
       (and (= begin-position 0)
            (re-matches #"\W" (subs string
                                    end-position
                                    (+ end-position 1))))
       (word-indices remaining-string word (conj indices new-index)
                     (+ end-position offset))
       ;; not at the beginning or ending of the string
       ;; and not in the middle of a word
       (and (re-matches #"\W" (subs string
                                    (+ begin-position -1)
                                    begin-position))
            (re-matches #"\W" (subs string
                                    end-position
                                    (+ end-position 1))))
       (word-indices remaining-string word (conj indices new-index) (+ end-position offset))
       :else
       (word-indices remaining-string word
                     indices
                     (+ end-position offset)
                     )))))

(defn word-indices->word-indices-map
  "Given a word-indices map returned by word-indices, create a vector of
  {:word <word> :index <index>} maps"
  [word-indices]
  (mapv #(hash-map :word (:word word-indices)
                   :index %)
        (:indices word-indices)))

(defn annotation-map->word-indices-maps
  "Given a string and an annotation-map of the form:
  {:word <string> :annotation <string>}

  return a vector of maps of the form:

  [{:word <string> :index [[<begin> <end>]..] :annotation <string>} ...]
  which are indexed to string"
  [string {:keys [word annotation color]}]
  (mapv (partial merge {:annotation annotation
                        :color color})
        (word-indices->word-indices-map (word-indices string word))))

(defn annotations->word-indices-maps
  "Given a string and a vector of annotations maps of the form
  [{:word <string> :annotation <string>}..]

  return a vector of maps for the form
  [{:word <string> :index [[<begin> <end>] ..] :annotation <string>} ...]

  which are indexed to string"
  [annotations string]
  (->> annotations
       (mapv (partial annotation-map->word-indices-maps string))
       flatten
       (sort-by #(first (:index %)))
       (into [])))

(defn annotations->no-annotations-indices-maps
  "Given a string and vector of indexed annotations,
  return the indices for which there are no annotations in string.
  Returns a vector of the form
  [{:word nil :index [[<begin> <end>] ..] :annotation <string>} ...]"
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
   [{:word <string> :index [[<begin> <end>] ..] :annotation <string>} ...]
  for annotations."
  (->> (annotations->word-indices-maps annotations string)
       flatten
       (sort-by #(first (:index %)))
       (into [])))

(defn remove-overlapping-indices
  "Given a coll of maps with a {:index [start end]} keyword-value sorted by :index,
  remove all but the longest overlapping maps from coll"
  ([annotation-indices]
   (if (empty? annotation-indices)
     annotation-indices
     (remove-overlapping-indices [] annotation-indices (first annotation-indices))))
  ([current-maps rest-maps longest-map]
   (if (empty? rest-maps)
     ;; we're done
     (conj current-maps longest-map)
     ;; we have more work to do
     (if (overlap? (:index longest-map)
                   (:index (first rest-maps)))
       ;; there is overlap
       (remove-overlapping-indices current-maps
                                   (rest rest-maps)
                                   (max-key
                                    #(index-length (:index %))
                                    longest-map
                                    (first rest-maps)))
       ;; there is no overlap, insert longest-map
       (remove-overlapping-indices (conj current-maps
                                         longest-map)
                                   (rest rest-maps)
                                   ;; starting over
                                   (first rest-maps))))))

(defn process-annotations
  "Given a set of annotations and a string, return a vector of the form
  [{:word <string> :index [[<begin> <end>] ..] :annotation <string>} ...]

  non-annotated indices will have the value of nil for :word
  overlapping maps will be resolved to the longest annotation"
  [annotations string]
  (let [annotation-indices (annotation-indices annotations string)
        final-annotations (remove-overlapping-indices annotation-indices)
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
  [{:keys [text content text-decoration]
    :or {text-decoration
         "underline dotted #909090"}}]
  (let [highlight-text [:span
                        {:style
                         {:text-decoration text-decoration
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

#_(defn AnnotatedText
  [text annotations & [text-decoration]]
  (let [annotations (process-annotations annotations text)]
    [:div.annotated-text
     (map (fn [{:keys [word index annotation]}]
            (let [key (str (gensym word))]
              (if (= word nil)
                ^{:key key}
                (apply (partial subs text) index)
                ^{:key key}
                [Annotation (cond->
                                {:text (apply (partial subs text) index)
                                 :content annotation}
                              text-decoration
                              (merge {:text-decoration text-decoration}))])))
          annotations)]))

(defn convert-annotations
  "Given a coll of annotation maps in the form
   {:semantic-class <string> ; semantic class, optionally nil
    :annotation <string> ; annotation for popup
    :context {:start-offset <integer>
              :end-offset <integer>
              :selection <string> ; the text corresponding to the annotation
              :text-context <string> ; the context from which a selection was taken}}}
  and a text that contains text-context, return a set of annotations in the form
  [{:start <integer> ;; start index in text
    :end   <integer> ;; end index in text
    :annotation <string> ;; will contain semantic-class: annotation}]"
  [annotations text]
  (let [;; get all contexts and determine their relative offset in selection
        contexts (->> annotations
                      (map #(get-in % [:context :text-context]))
                      set
                      (map #(hash-map % (let [start (clojure.string/index-of text %)
                                              end (+ start (count %))]
                                          [start end])))
                      (apply merge))
        processed-annotations (map #(let [{:keys [selection context semantic-class annotation]} %
                                          {:keys [start-offset end-offset text-context]} context
                                          start (+ start-offset (first (get contexts text-context)))
                                          end (+ end-offset (first (get contexts text-context)))]
                                         (hash-map :start start
                                                   :end end
                                                   :semantic-class semantic-class
                                                   :annotation annotation
                                                   :selection selection
                                                   :text-context text-context))
                                      annotations)]
    processed-annotations))

(defn html-text->text
  "Strip all html tags and convert all character entity references (e.g. &lt;) to their single char representation
  in html-text"
  [html-text]
  (let [span ($ js/document createElement "SPAN")
        _ ($! span :innerHTML html-text)]
    ($ span :innerText)))

(defn remove-overlaps
  "Remove all but the longest overlapping annotations"
  [annotations]
  (->> annotations
       (map #(assoc % :index [(:start %)
                              (:end %)]))
       (sort-by :index)
       remove-overlapping-indices))

(defn highlight-text-div-string
  "Generate the string to pass to cljs.reader for a div that contains text highlighted with annotations."
  [annotations text]
  (let [text (-> text
                 (clojure.string/replace #"\n+" "")
                 html-text->text)
        annotations (-> (convert-annotations annotations text)
                        remove-overlaps)
        max-index (- (count text) 1)
        start-map (vector->hash-map annotations :start)
        end-map (vector->hash-map annotations :end)
        escaped-item (fn [item] (if (= item "\"")
                                  "\\\""
                                  item))]
    (str "[:div "
         (->> (map-indexed
               (fn [idx item]
                 (cond
                   ;; on first item, but it also starts highlight
                   (and (= idx 0)
                        (get start-map idx))
                   (str "[:div [:span {:style {:background-color \"black\" :color \"white\"}} \"" (escaped-item item))
                   ;; on first item, no highlights
                   (= idx 0)
                   (str "[:div \"" (escaped-item item))
                   ;; on the last item, but it also starts highlight
                   (and (= idx max-index)
                        (get start-map idx))
                   (str "\"[:span {:style {:background-color \"black\" :color \"white\"}} \"" item "\"]]")
                   ;; on the last item, close out
                   (= idx max-index)
                   (str (escaped-item item) "\"]]")
                   ;; a new line
                   (= (escaped-item item) "\n")
                   ;; just skip it
                   ;;"\"] [:span {:dangerouslySetInnerHTML {:__html \"&nbsp;\"}}] [:div \""
                   ""
                   ;; match for start index at idx
                   (get start-map idx)
                   (str "\" [:span {:style {:background-color \"black\" :color \"white\"}} \"" (escaped-item item))
                   ;; match for end index at idx
                   (get end-map idx)
                   (str "\"]\"" (escaped-item item))
                   ;;(str "\"][[\"" item) ; reader-error-render is used instead for testing purposes
                   ;; nothing special to be done
                   :else
                   (escaped-item item)))
               text)
              (apply str))
         "]")))

;;(def annotations-atom (r/atom {}))
;;(def text-atom (r/atom ""))
(defn AnnotatedText
  "Return a div with text highlighted by annotations. The class name for the annotated div is given as :class, defaults to 'annotated-text'"
  [annotations text & {:keys [text-decoration reader-error-render field]
                       :or {reader-error-render [:div "There was an error rendering the annotator view"]
                            field "annotated-text"}}]
  ;;(reset! text-atom text)
  ;;(reset! annotations-atom annotations)
  [:div {:data-field field}
   (try (cljs.reader/read-string
         (highlight-text-div-string annotations text))
        (catch js/Object e
          reader-error-render))])
