(ns sysrev.annotation
  (:require [cljsjs.rangy-selectionsaverestore]
            [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :refer [get-input-value]]
            [sysrev.views.components :refer [TextInput]])
  (:require-macros [reagent.interop :refer [$]]))

(def semantic-ui js/semanticUIReact)
(def Popup (r/adapt-react-class (goog.object/get semantic-ui "Popup")))

;; accessing state for testing:
;; @(r/cursor sysrev.views.article/state [:annotations 7978]))
;; @(subscribe [:article/abstract 7978])

(def-action :annotations/create-annotation
  :uri (fn [] "/api/annotation")
  :content (fn [annotation-map]
             annotation-map)
  :process (fn [_ _ result]
             (.log js/console "[[create-annotation saved on server]]")
             {}))

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
  [{:word <string :annotation <string>}..]

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
  "Given a set of index-maps returned from annotation-indices, remove all but the longest overlapping annotation"
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

(defn AnnotatedText
  [text annotations & [text-decoration]]
  (let [annotations (process-annotations annotations text)]
    [:div
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

(defn AnnotationEditor
  [{:keys [annotation-atom user-annotations]}]
  (let [{:keys [selection rank]} @annotation-atom]
    [:div
     [:div
      [:div [:div {:style {:cursor "pointer"}
                   :on-click (fn [event]
                               (swap! user-annotations dissoc rank))}
             [:i.times.icon]] [:h3 selection]]
      [TextInput {:value (r/cursor annotation-atom [:annotation])
                  :on-change (fn [event]
                               (reset! (r/cursor annotation-atom [:annotation])
                                       (get-input-value event)))
                  :label "Annotation"}]
      [:br]]]))

(defn AddAnnotation
  [state]
  (let [user-annotations (r/cursor state [:user-annotations])
        last-rank (if-let [max-rank (->> (vals @user-annotations)
                                         (map :rank)
                                         (apply max))]
                    (inc max-rank)
                    1)
        editing? (r/cursor state [:editing?])
        new-annotation-map (r/cursor state [:new-annotation-map])
        selection (r/atom state [:selection])
        annotation (r/cursor new-annotation-map [:annotation])
        current-selection (r/atom "")]
    (fn [state]
      (let [left (r/cursor state [:client-x])
            top (r/cursor state [:client-y])
            selection (r/cursor state [:selection])]
        [:div {:style {:top (str (- @top 100) "px")
                       :left (str @left "px")
                       :position "fixed"
                       :z-index "100"
                       :background "black"}
               :on-click (fn [e]
                           ($ e stopPropagation)
                           ($ e preventDefault))
               :on-mouse-up (fn [e]
                              ($ e preventDefault)
                              ($ e stopPropagation))
               :on-mouse-down (fn [e]
                                (reset! current-selection (-> ($ js/rangy saveSelection))))}
         (if @editing?
           (let [on-save (fn []
                           (swap! user-annotations merge {last-rank @new-annotation-map})
                           (dispatch [:action [:annotations/create-annotation
                                               {:selection @selection
                                                :annotation @annotation
                                                :article-id @(subscribe [:visible-article-id])}]])
                           (reset! editing? false)
                           (reset! selection ""))]
             [:div
              [:div
               [:label "Selection"]
               [:p @selection]]
              [:form {:on-submit (fn [e]
                                   ($ e preventDefault)
                                   ($ e stopPropagation)
                                   (on-save))}
               [TextInput {:value annotation
                           :on-change (fn [event]
                                        (reset! annotation (get-input-value event)))
                           :autofocus true
                           :label "Annotation"}]
               [:div.ui.fluid.button
                {:on-click (fn [e]
                             (on-save))}
                "Save"]
               [:div.ui.fluid.button
                {:on-click (fn [e]
                             (reset! editing? false)
                             (-> ($ js/rangy getSelection)
                                 ($ removeAllRanges))
                             (reset! selection ""))}
                "Dismiss"]]])
           [:h1 {:on-click (fn [e]
                             ($ e stopPropagation)
                             ($ e preventDefault)
                             (reset! editing? true)
                             (reset! new-annotation-map {:selection @selection
                                                         :annotation ""
                                                         :rank last-rank}))} "Annotate Selection"])]))))

(defn AnnotationMenu
  [state]
  (let [user-annotations (r/cursor state [:user-annotations])]
    (fn [state]
      [:div
       {:style {:top "0px"
                :left "0px"
                :height "100%"
                :width "10%"
                :z-index "100"
                :position "fixed"}}
       [:h1 "Annotations"]
       (when-not (empty? (vals @user-annotations))
         (map
          (fn [{:keys [rank]}]
            ^{:key (str "rank-" rank)}
            [AnnotationEditor {:annotation-atom (r/cursor user-annotations [rank])
                               :user-annotations user-annotations}])
          (sort-by :rank (vals @user-annotations))))])))

(def state (r/atom {}))

(defn AnnotationCapture
  [child]
  (let [selection (r/cursor state [:selection])
        client-x (r/cursor state [:client-x])
        client-y (r/cursor state [:client-y])
        editing? (r/cursor state [:editing?])]
    (fn [child]
      [:div
       [AnnotationMenu state]
       [:div {:on-mouse-up (fn [e]
                             (reset! selection (-> ($ js/rangy getSelection)
                                                   ($ toString)))
                             (reset! client-x ($ e :clientX))
                             (reset! client-y ($ e :clientY))
                             (reset! editing? false))}
        (when-not (empty? @selection)
          [AddAnnotation state])
        child]])))
