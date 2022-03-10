(ns sysrev.annotation
  (:require cljs.reader
            [clojure.string :as str]
            [sysrev.util :as util :refer [index-by]]))

;; accessing state for testing:
;; @(r/cursor sysrev.views.article/state [:annotations 7978]))
;; @(subscribe [:article/abstract 7978])

(defn within?
  "Given a coll of sorted numbers, determine if x is within (inclusive) the range of values"
  [x coll]
  (and (>= x (first coll)) (<= x (last coll))))

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
                      (map #(hash-map % (let [start (str/index-of text %)
                                              end (+ start (count %))]
                                          [start end])))
                      (apply merge))]
    (map #(let [{:keys [selection context semantic-class annotation]} %
                {:keys [start-offset end-offset text-context]} context
                start (+ start-offset (first (get contexts text-context)))
                end (+ end-offset (first (get contexts text-context)))]
            (hash-map :start start
                      :end end
                      :semantic-class semantic-class
                      :annotation annotation
                      :selection selection
                      :text-context text-context))
         annotations)))

(defn html-text->text
  "Strip all html tags and convert all character entity
  references (e.g. &lt;) to their single char representation in
  html-text"
  [html-text]
  (let [span (js/document.createElement "SPAN")]
    (set! (.-innerHTML span) html-text)
    (.-innerText span)))

(defn remove-overlaps
  "Remove all but the longest overlapping annotations"
  [annotations]
  (->> annotations
       (map #(assoc % :index [(:start %) (:end %)]))
       (sort-by :index)
       remove-overlapping-indices))

(defn highlight-text-div-string
  "Generate the string to pass to cljs.reader for a div that contains
  text highlighted with annotations."
  [annotations text]
  (let [text (-> text
                 (str/replace #"\n+" "")
                 html-text->text)
        annotations (-> (convert-annotations annotations text)
                        remove-overlaps)
        max-index (- (count text) 1)
        start-map (index-by :start annotations)
        end-map (index-by :end annotations)
        escaped-item #(if (= % "\"") "\\\"" %)]
    (str "[:div "
         (->> (map-indexed
               (fn [idx item]
                 (cond
                   ;; on first item, but it also starts highlight
                   (and (= idx 0)
                        (get start-map idx))
                   (str "[:div [:span.annotated-text \"" (escaped-item item))
                   ;; on first item, no highlights
                   (= idx 0)
                   (str "[:div \"" (escaped-item item))
                   ;; on the last item, but it also starts highlight
                   (and (= idx max-index)
                        (get start-map idx))
                   (str "\"[:span.annotated-text \"" item "\"]]")
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
                   (str "\" [:span.annotated-text \"" (escaped-item item))
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
  "Return a div with text highlighted by annotations. field argument
  provides a data-field value for the top-level div."
  [annotations text & {:keys [text-decoration reader-error-render field]
                       :or {field "annotated-text"}}]
  ;;(reset! text-atom text)
  ;;(reset! annotations-atom annotations)
  [:div {:data-field field :class "annotated-text-toplevel"}
   (try (cljs.reader/read-string
         (highlight-text-div-string annotations text))
        (catch js/Object _
          (or reader-error-render
              [:div "There was an error rendering the annotator view"])))])
