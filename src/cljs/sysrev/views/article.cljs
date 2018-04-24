(ns sysrev.views.article
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            goog.object
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.annotation :refer [AnnotatedText]]
            [sysrev.views.keywords :refer [render-keywords render-abstract]]
            [sysrev.views.components :refer [out-link document-link]]
            [sysrev.views.labels :refer
             [label-values-component article-labels-view]]
            [sysrev.util :refer [full-size? nbsp continuous-update-until]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def state (r/atom nil))

(def-data :article/annotations
  :loaded? (fn [_ article-id _]
             (not (nil? @(r/cursor state [:annotations article-id]))))
  :uri (fn [article-id] (str "/api/annotations/" article-id ))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id string] )
  :process (fn [_ [article-id string] result]
             (swap! state assoc-in [:annotations article-id] (:annotations result))
             {}))

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

(defn slug-string->sentence-string
  "Convert a slug string into a normal English sentence.
  ex: gene_or_gene_product -> Gene or gene product"
  [string]
  (-> string
      (clojure.string/replace #"_" " ")))

(defn process-annotations
  [raw-annotations]
  (->> raw-annotations
       ;; filter out everything but reach
       (filterv #(= "reach"
                    (:ontology %)))
       ;; process into an overlay
       (mapv #(assoc %
                     :word (:name %)
                     :annotation
                     (str (slug-string->sentence-string (:semantic_class %)))))
       ;; remove duplicates
       (group-by #(clojure.string/lower-case (:word %)))
       vals
       (map first)))

(defn get-annotations
  "Get annotations with a delay of seconds, defaults to 30"
  [article-id & {:keys [delay]
                 :or {delay 30}}]
  (let [last-annotation-call (r/cursor state [:last-annotation-call])]
    (reset! last-annotation-call {:time (cljs-time.core/now)
                                  :article-id article-id})
    (continuous-update-until
     ;; throw away fn
     (constantly true)
     ;; predicate to check to see if 30 seconds has elapsed
     #(cljs-time.core/after?
       ;; current time
       (cljs-time.core/now)
       ;; time stored in last-annotation-call, plus 30 seconds
       (cljs-time.core/plus (:time @last-annotation-call)
                            (cljs-time.core/seconds delay)))
     ;; on-success
     ;; if the article-id is still the same, fetch the article
     #(if (= article-id
             (:article-id @last-annotation-call))
        (dispatch [:fetch [:article/annotations article-id]]))
     100)))

(defn article-info-main-content [article-id & {:keys [context]}]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:article project-id article-id]] {}
      (let [authors @(subscribe [:article/authors article-id])
            journal-name @(subscribe [:article/journal-name article-id])
            title @(subscribe [:article/title article-id])
            journal-render @(subscribe [:article/journal-render article-id])
            abstract @(subscribe [:article/abstract article-id])
            urls @(subscribe [:article/urls article-id])
            documents @(subscribe [:article/documents article-id])
            date @(subscribe [:article/date article-id])
            annotations (condp = context
                          :article-list
                          (process-annotations @(r/cursor state [:annotations article-id]))
                          :review
                          (->> @(subscribe [:project/keywords])
                               vals
                               (mapv :value)
                               (mapv #(hash-map :word %))))
            delay 5]
        (when (= context
                 :article-list)
          (get-annotations article-id :delay delay))
        [:div
         [:h3.header
          (when-not (empty? title)
            [AnnotatedText title annotations
             (if (= context
                    :review)
               "underline green"
               "underline #909090")])]
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
         ;; abstract, with annotations
         (when-not (empty? abstract)
           [AnnotatedText abstract annotations
            (if (= context
                   :review)
              "underline green")])
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
                           urls))]])]))))

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
  [article-id & {:keys [show-labels? private-view? show-score?
                        context]
                 :or {show-score? true}}]
  (let [project-id @(subscribe [:active-project-id])
        status @(subscribe [:article/review-status article-id])
        full-size? (full-size?)
        score @(subscribe [:article/score article-id])]
    [:div
     (with-loader [[:article project-id article-id]]
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
         [article-info-main-content article-id
          :context context]]))
     (when show-labels?
       [article-labels-view article-id :self-only? private-view?])]))
