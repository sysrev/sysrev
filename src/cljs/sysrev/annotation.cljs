(ns sysrev.annotation
  (:require [cljsjs.rangy-selectionsaverestore]
            [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [re-frame.core :as re-frame :refer [subscribe dispatch reg-event-fx]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.util :refer [get-input-value vector->hash-map]]
            [sysrev.views.components :refer [TextInput]])
  (:require-macros [reagent.interop :refer [$]]))

(def default-annotator-state {:annotation-retrieving? false
                              :annotator-enabled? false})

(def abstract-annotator-state (r/atom (assoc default-annotator-state
                                             :context {:class "abstract"})))

(def semantic-ui js/semanticUIReact)
(def Popup (r/adapt-react-class (goog.object/get semantic-ui "Popup")))
(def Dropdown (r/adapt-react-class (goog.object/get semantic-ui "Dropdown")))
;; accessing state for testing:
;; @(r/cursor sysrev.views.article/state [:annotations 7978]))
;; @(subscribe [:article/abstract 7978])

(def-action :annotation/create-annotation
  :uri (fn []
         "/api/annotation")
  :content (fn [annotation-map state]
             {:annotation-map
              annotation-map
              :context @(r/cursor state [:context])})
  :process (fn [_ [annotation-map state] result]
             (dispatch [:reload [:annotation/user-defined-annotations
                                 @(subscribe [:visible-article-id])
                                 state]])
             (reset! (r/cursor state [:annotation-retrieving?]) false)
             {}))


(def-action :annotation/update-annotation
  :uri (fn [annotation-id] (str "/api/annotation/update/" annotation-id))
  :content (fn [annotation-id annotation semantic-class]
             {:annotation annotation
              :semantic-class semantic-class})
  :process (fn [_ _ result]
             {}))

(def-action :annotation/delete-annotation
  :uri (fn [annotation-id] (str "/api/annotations/delete/"
                                annotation-id))
  :content (fn [annotation-id]
             annotation-id)
  :process (fn [_ _ result]
             {}))

(def-data :annotation/user-defined-annotations
  :loaded? (fn [_ _ _]
             (constantly false))
  :uri (fn [article-id state]
         (condp = @(r/cursor state [:context :class])
           "abstract"
           (str "/api/annotations/user-defined/"
                article-id)
           "pdf"
           (str "/api/annotations/user-defined/"
                article-id "/pdf/" @(r/cursor state [:context :pdf-key]))))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id state])
  :process (fn [_ [article-id state] result]
             (let [annotations (:annotations result)]
               (when-not (nil? annotations)
                 (reset! (r/cursor state [:user-annotations])
                         (vector->hash-map annotations :id))))
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
  (let [editing? (r/cursor annotation-atom [:editing?])
        edited-annotation (r/atom "")
        edited-semantic-class (r/atom "")
        labels (->> (get-in @app-db [:data :project (active-project-id @app-db) :labels])
                    vals
                    (filter :enabled))]
    (fn [{:keys [annotation-atom user-annotations]}]
      (let [{:keys [selection id]} @annotation-atom
            original-annotation (-> @annotation-atom
                                    :annotation)
            original-semantic-class (-> @annotation-atom
                                        :semantic-class)
            annotation (r/cursor annotation-atom [:annotation])
            semantic-class (r/cursor annotation-atom [:semantic-class])
            on-save (fn []
                      (reset! editing? false)
                      (reset! annotation @edited-annotation)
                      (reset! semantic-class @edited-semantic-class)
                      (dispatch [:action [:annotation/update-annotation id @edited-annotation @semantic-class]]))
            last-semantic-class (->> (vals @user-annotations)
                                     (filter #(not (nil? (:semantic-class %))))
                                     (sort-by :id)
                                     reverse
                                     first
                                     :semantic-class)]
        (when (empty? @semantic-class)
          (reset! edited-semantic-class last-semantic-class))
        [:div
         [:div
          [:div [:div {:style {:cursor "pointer"
                               :float "right"}
                       :on-click (fn [event]
                                   (swap! user-annotations dissoc id)
                                   (dispatch [:action [:annotation/delete-annotation id]]))}
                 [:i.times.icon]]
           [:br]
           [:h3 {:class "ui grey header"} (str "\"" selection "\"")]]
          [:br]
          (when (empty? @semantic-class)
            (reset! editing? true))
          (if @editing?
            [:form {:on-submit (fn [e]
                                 ($ e preventDefault)
                                 ($ e stopPropagation)
                                 (on-save))}
             [:div
              [TextInput {:value edited-semantic-class
                          :on-change (fn [e]
                                       (reset! edited-semantic-class
                                               (get-input-value e)))
                          :label "Semantic Class"}]
              [TextInput {:value edited-annotation
                          :on-change (fn [event]
                                       (reset! edited-annotation
                                               (get-input-value event)))
                          :label "Value"}]
              [:br]
              [:div.ui.small.button
               {:on-click (fn [e]
                            (on-save))}
               "Save"]
              (when-not (empty? @annotation)
                [:div.ui.small.button
                 {:on-click (fn [e]
                              (reset! editing? false)
                              (reset! edited-annotation "")
                              (reset! edited-semantic-class ""))}
                 "Dismiss"])]]
            [:div
             (when-not (empty? @semantic-class)
               [:label "Semantic Class"
                [:h3 @semantic-class]])
             (when-not (empty? @annotation)
               [:label "Value"
                [:h3 @annotation]])
             [:br]
             [:div.ui.small.button
              {:on-click (fn [e]
                           (reset! editing? true)
                           (reset! edited-annotation original-annotation)
                           (reset! edited-semantic-class original-semantic-class))}
              "Edit"]])
          [:br]]]))))

(defn AddAnnotation
  "Create an AddAnnotation using state"
  [state]
  (let [user-annotations (r/cursor state [:user-annotations])
        new-annotation-map (r/cursor state [:new-annotation-map])
        selection (r/atom state [:selection])
        annotation (r/cursor new-annotation-map [:annotation])
        current-selection (r/atom "")]
    (fn [state]
      (let [left (r/cursor state [:client-x])
            top (r/cursor state [:client-y])
            selection (r/cursor state [:selection])
            on-save (fn []
                      (dispatch [:action [:annotation/create-annotation
                                          {:selection @selection
                                           :annotation @annotation
                                           :article-id @(subscribe [:visible-article-id])}
                                          state]]))]
        [:div {:style {:top (str (- @top 100) "px")
                       :left (str @left "px")
                       :position "fixed"
                       :z-index "100"
                       :background "black"
                       :cursor "pointer"}
               :on-click (fn [e]
                           ($ e stopPropagation)
                           ($ e preventDefault))
               :on-mouse-up (fn [e]
                              ($ e preventDefault)
                              ($ e stopPropagation))
               :on-mouse-down (fn [e]
                                (reset! current-selection (-> ($ js/rangy saveSelection))))}
         [:h1 {:class "ui grey header"
               :on-click (fn [e]
                           ($ e stopPropagation)
                           ($ e preventDefault)
                           (reset! (r/cursor state [:annotation-retrieving?]) true)
                           (reset! new-annotation-map {:selection @selection
                                                       :annotation ""})
                           (on-save)
                           (-> ($ js/rangy getSelection)
                               ($ removeAllRanges))
                           (reset! selection ""))} "Annotate Selection"]]))))

(defn AnnotationMenu
  "Create an annotation menu using state."
  [state]
  (let [user-annotations (r/cursor state [:user-annotations])
        retrieving? (r/cursor state [:annotation-retrieving?])
        annotator-enabled? (r/cursor state [:annotator-enabled?])]
    (fn [state]
      (when @annotator-enabled?
        [:div.ui.segment
         {:style {;; :top "0px"
                  ;; :left "0px"
                  :height "100%"
                  ;;:width "10%"
                  ;; :z-index "100"
                  ;; :position "fixed"
                  :overflow-y "auto"}}
         [:h1 "Annotations"]
         (when @retrieving?
           [:div.item.loading.indicator
            [:div.ui.small.active.inline.loader]])
         (when-not (empty? (vals @user-annotations))
           (map
            (fn [{:keys [id]}]
              ^{:key (str "id-" id)}
              [AnnotationEditor {:annotation-atom (r/cursor user-annotations [id])
                                 :user-annotations user-annotations}])
            (reverse (sort-by :id (vals @user-annotations)))))]))))

(defn AnnotationCapture
  "Create an Annotator using state. A child is a single element which has text
  to be captured"
  [state child]
  (let [selection (r/cursor state [:selection])
        client-x (r/cursor state [:client-x])
        client-y (r/cursor state [:client-y])
        editing? (r/cursor state [:editing?])
        annotator-enabled? (r/cursor state [:annotator-enabled?])
        annotation-context-class (r/cursor state [:context :class])]
    #_(when-not (= @annotation-context-class
                 "open access pdf"))
    (dispatch [:reload [:annotation/user-defined-annotations
                        @(subscribe [:visible-article-id])
                        state]])
    (fn [state child]
      [:div.annotation-capture
       {:on-mouse-up (fn [e]
                       (when @annotator-enabled?
                         (reset! selection (-> ($ js/rangy getSelection)
                                               ($ toString)))
                         (reset! client-x ($ e :clientX))
                         (reset! client-y ($ e :clientY))
                         (reset! editing? false)))}
       (when-not (empty? @selection)
         [AddAnnotation state])
       child])))

(defn AnnotationToggleButton
  [state]
  (let [annotator-enabled? (r/cursor state [:annotator-enabled?])]
    [:div.ui.label.tiny.button
     {:on-click (fn [e]
                  (swap! annotator-enabled? not))}
     (if @annotator-enabled?
       "Disable Annotator"
       "Enable Annotator")]))
