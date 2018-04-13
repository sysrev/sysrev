(ns sysrev.views.article
  (:require
   [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   goog.object
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer [subscribe dispatch]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.views.keywords :refer [render-keywords render-abstract]]
   [sysrev.views.components :refer [out-link document-link]]
   [sysrev.views.labels :refer
    [label-values-component article-labels-view]]
   [sysrev.util :refer [full-size? nbsp]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def semantic-ui js/semanticUIReact)
(def Popup (r/adapt-react-class (goog.object/get semantic-ui "Popup")))

(def state (r/atom nil))

(def-data :annotations
  :loaded? (fn [_ _ _]
             (constantly false))
  :uri (fn [] "/api/annotations")
  :prereqs (fn [] [[:identity]])
  :content (fn [string] {:string string})
  :process (fn [_ _ result]
             (.log js/console "I loaded")
             (swap! state assoc-in [:annotations] result)
             {}))

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
         begin-position (clojure.string/index-of string word)
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

(defn annotations->none-indices-maps
  "Given a string and vector of annotations, return the maps for which there are no annotations in string"
  [annotations string]
  (let [occupied-chars (sort (flatten (mapv :index (annotations->word-indices-maps annotations string))))
        none-indices (merge (mapv #(vector %1 %2)
                                  (take-nth 2 (rest occupied-chars))
                                  (rest (take-nth 2 occupied-chars)))
                            [(last occupied-chars)])]
    (-> (mapv #(hash-map :word "none" :annotation nil :index %) none-indices)
        (merge {:index [0 (first occupied-chars)]
                :annotation nil
                :word "none"}))))

(defn annotation-indices
  [annotations string]
  (->> (merge (annotations->word-indices-maps annotations string)
              (annotations->none-indices-maps annotations string))
       flatten
       (sort-by #(first (:index %)))
       (into [])))
;; this corresponds to 'Novel therapies in development for metastatic colorectal cancer'
;; in 'Novel Dose Escalation Methods and Phase I Designs' project
(def string @(subscribe [:article/abstract 12184]))

(defn Annotation
  [{:keys [text content color]
    :or {color "#909090"}}]
  [Popup {:trigger (r/as-element [:span {:style {:text-decoration (str "underline dotted " color)
                                                 :cursor "pointer"}} text])
          :content content }])

(def annotations
  [{:word "cancer"
    :annotation "A disease in which abnormal cells divide uncontrollably and destroy body tissue."}
   {:word "metastatic CRC"
    :annotation "metastatic colorectal cancer"}])

(defn AnnotatedText
  [text]
  (let [word "metastatic CRC"
        annotation "metastatic colorectal cancer"
        word-indices (word-indices text word)
        annotations (annotation-indices annotations string)]
    [:div
     (map (fn [{:keys [word index annotation]}]
            (let [key (str (gensym word))]
              (if (= word "none")
                ^{:key key}
                (apply (partial subs text) index)
                ^{:key key}
                [Annotation {:text word
                             :content annotation}])))
          annotations
          )]))

(defn- author-names-text [nmax coll]
  (let [show-list (take nmax coll)
        display (str/join ", " show-list)
        extra (when (> (count coll) nmax) " et al")]
    (str display extra)))

(defn- article-score-label [score]
  (when (and score
             (not= score 0)
             (not= score 0.0))
    (let [icon (if (> score 0.5)
                 "plus" "minus")
          percent (-> score (* 100) (+ 0.5) int)]
      [:div.ui.label.article-score
       "Prediction"
       [:div.detail
        [:span
         [:i {:class (str "grey " icon " circle icon")}]
         (str percent "%")]]])))

(defn- review-status-label [status]
  (let [resolving? @(subscribe [:review/resolving?])
        sstr
        (cond (= status :user)         "User view"
              (= status :resolved)     "Resolved"
              resolving?               "Resolving conflict"
              (= status :conflict)     "Conflicting labels"
              (= status :single)       "Reviewed by one user"
              (= status :consistent)   "Consistent labels"
              (= status :unreviewed)   "Not yet reviewed"
              :else                    nil)
        color
        (cond (= status :resolved)     "purple"
              (= status :conflict)     "orange"
              (= status :consistent)   "green"
              :else                    "")]
    (when sstr
      [:div.ui.basic.label.review-status
       {:class color}
       (str sstr)])))

(defn article-info-main-content [article-id]
  (with-loader [[:article article-id]] {}
    (let [authors @(subscribe [:article/authors article-id])
          journal-name @(subscribe [:article/journal-name article-id])
          abstract @(subscribe [:article/abstract article-id])
          title-render @(subscribe [:article/title-render article-id])
          journal-render @(subscribe [:article/journal-render article-id])
          urls @(subscribe [:article/urls article-id])
          documents @(subscribe [:article/documents article-id])
          date @(subscribe [:article/date article-id])]
      [:div
       [:h3.header
        [render-keywords article-id title-render
         {:label-class "large"}]]
       (when-not (empty? journal-name)
         [:h3.header {:style {:margin-top "0px"}}
          [render-keywords article-id journal-render
           {:label-class "large"}]])
       (when-not (empty? date)
         [:h5.header {:style {:margin-top "0px"}}
          date])
       (when-not (empty? authors)
         [:h5.header {:style {:margin-top "0px"}}
          (author-names-text 5 authors)])
       (when-not (empty? abstract)
         ;;[:h1 "Foo"]
         ;;(.log js/console abstract)
         [AnnotatedText abstract]
         ;;[render-abstract article-id]
         ;;[:h1 "foo"]
         )
       ;; article file links went here (article-docs-component)
       (when-not (empty? documents)
         [:div {:style {:padding-top "0.75em"}}
          [:div.content.ui.horizontal.list
           (doall
            (map-indexed (fn [idx {:keys [fs-path url]}]
                           ^{:key [idx]} [document-link url fs-path])
                         documents))]])
       (when-not (empty? urls)
         [:div {:style {:padding-top "0.75em"}}
          [:div.content.ui.horizontal.list
           (doall
            (map-indexed (fn [idx url]
                           ^{:key [idx]} [out-link url])
                         urls))]])])))

(defn- article-flag-label [description]
  [:div.ui.left.labeled.button.article-flag
   [:div.ui.basic.label
    [:i.fitted.flag.icon
     {:style {:padding-left "0.25em"
              :padding-right "0.25em"
              :margin "0"}}]]
   [:div.ui.small.orange.basic.button description]])

(defn- article-flags-view [article-id & [wrapper-class]]
  (let [flag-labels {"user-duplicate" "Duplicate article (exclude)"
                     "user-conference" "Conference abstract (exclude)"}
        flags @(subscribe [:article/flags article-id])
        flag-names (->> (keys flags)
                        (filter #(get flag-labels %))
                        sort)
        entries (for [flag-name flag-names]
                  ^{:key flag-name}
                  [article-flag-label (get flag-labels flag-name)])]
    (when (not-empty flag-names)
      (if wrapper-class
        [:div {:class wrapper-class}
         (doall entries)]
        (doall entries)))))

(defn article-info-view
  [article-id & {:keys [show-labels? private-view? show-score?]
                 :or {show-score? true}}]
  (let [status @(subscribe [:article/review-status article-id])
        full-size? (full-size?)
        score @(subscribe [:article/score article-id])]
    [:div
     (with-loader [[:article article-id]]
       {:class "ui segments article-info"}
       (list
        [:div.ui.top.attached.middle.aligned.header
         {:key [:article-header]}
         [:div {:style {:float "left"}}
          [:h4 "Article Info "
           (when full-size? (article-flags-view article-id nil))]]
         (when (or status private-view?)
           [:div {:style {:float "right"}}
            (when (and score show-score? (not= status :single))
              [article-score-label score])
            [review-status-label (if private-view? :user status)]])
         [:div {:style {:clear "both"}}]]
        (when-not full-size? (article-flags-view article-id "ui attached segment"))
        [:div.ui.attached.segment
         {:key [:article-content]}
         [article-info-main-content article-id]
         ]))
     (when show-labels?
       [article-labels-view article-id :self-only? private-view?])]))
