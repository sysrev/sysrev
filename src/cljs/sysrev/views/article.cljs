(ns sysrev.views.article
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            goog.object
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.annotation :as annotation :refer [AnnotatedText AnnotationCapture AnnotationToggleButton AnnotationMenu]]
            [sysrev.pdf :as pdf :refer [PDFs]]
            [sysrev.views.components :refer [out-link document-link]]
            [sysrev.views.keywords :refer [render-keywords render-abstract]]
            [sysrev.views.labels :refer
             [label-values-component article-labels-view]]
            [sysrev.util :refer [full-size? nbsp continuous-update-until]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(reg-sub
 ::article-annotations
 (fn [[_ article-id project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ article-id project-id]]
   (get-in project [:annotations article-id])))

(def-data :article/annotations
  :loaded?
  (fn [db project-id article-id _]
    (-> (get-in db [:data :project project-id :annotations])
        (contains? article-id)))
  :uri (fn [project-id article-id _]
         (str "/api/annotations/" article-id))
  :prereqs (fn [_ _ _] [[:identity]])
  :content (fn [project-id _ _] {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id article-id _] {:keys [annotations] :as result}]
    {:db (assoc-in db [:data :project project-id :annotations article-id]
                   (or annotations []))}))

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
                 :or {delay 3}}]
  (let [project-id @(subscribe [:active-project-id])
        article-id-state (subscribe [:visible-article-id])]
    (js/setTimeout
     (fn [_]
       (when (= article-id @article-id-state)
         (dispatch [:require [:article/annotations project-id article-id]])))
     (* delay 1000))))

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
            annotations-raw @(subscribe [::article-annotations article-id])
            annotations (condp = context
                          :article-list
                          (process-annotations annotations-raw)
                          :review
                          (->> @(subscribe [:project/keywords])
                               vals
                               (mapv :value)
                               (mapv #(hash-map :word %))))
            delay 5
            annotator-enabled? (r/cursor annotation/abstract-annotator-state
                                         [:annotator-enabled?])]
        (when (= context :article-list)
          (get-annotations article-id :delay delay))
        [AnnotationCapture
         annotation/abstract-annotator-state
         [:div
          [:h3.header
           (when-not (empty? title)
             (if @annotator-enabled?
               title
               [AnnotatedText title annotations
                (if (= context
                       :review)
                  "underline green"
                  "underline #909090")]))]
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
            (if @annotator-enabled?
              [:div abstract]
              [AnnotatedText abstract annotations
               (if (= context
                      :review)
                 "underline green")]))
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
                            urls))]])]]))))

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

(defn article-disabled-label []
  [:div.ui.basic.label.review-status.orange
   "Disabled"])

(defn article-duplicates-segment [article-id]
  (let [duplicates @(subscribe [:article/duplicates article-id])
        project-id @(subscribe [:active-project-id])]
    (when duplicates
      [:div.ui.segment
       {:key [:article-duplicates]}
       [:h5
        "Duplicate articles:"
        (doall
         (for [article-id (:article-ids duplicates)]
           [:span {:key article-id}
            nbsp nbsp
            [:a {:href (project-uri
                        project-id (str "/articles/" article-id))}
             (str "#" article-id)]]))]])))

(defn article-info-view
  [article-id & {:keys [show-labels? private-view? show-score?
                        context]
                 :or {show-score? true}}]
  (let [project-id @(subscribe [:active-project-id])
        status @(subscribe [:article/review-status article-id])
        full-size? (full-size?)
        score @(subscribe [:article/score article-id])
        duplicates @(subscribe [:article/duplicates article-id])]
    ;; get the user-defined annotations
    (dispatch [:fetch [:annotation/user-defined-annotations
                       @(subscribe [:visible-article-id])
                       annotation/abstract-annotator-state]])
    [:div
     (with-loader [[:article project-id article-id]]
       {:class "ui segments article-info"}
       (list
        [:div.ui.middle.aligned.header.segment.article-header
         {:key [:article-header]}
         [:div {:style {:float "left"}}
          [:h4.article-info "Article Info "
           (when full-size? (article-flags-view article-id nil))]]
         (when (or status private-view?)
           [:div {:style {:float "right"}}
            (when (:disabled? duplicates)
              [article-disabled-label])
            (when (and score show-score? (not= status :single))
              [article-score-label score])
            [AnnotationToggleButton annotation/abstract-annotator-state]
            [review-status-label (if private-view? :user status)]])
         [:div {:style {:clear "both"}}]]
        (article-duplicates-segment article-id)
        (when-not full-size? (article-flags-view article-id "ui segment"))
        [:div.ui.segment.article-content
         {:key [:article-content]}
         [:div {:style {:top "0px"
                        :left "0px"
                        :height "100%"
                        :width "10%"
                        :z-index "100"
                        :position "fixed"
                        ;;:overflow-y "auto"
                        }}
          [AnnotationMenu annotation/abstract-annotator-state]]
         [article-info-main-content article-id
          :context context]]
        ^{:key :article-pdfs}
        [PDFs article-id]))
     (when show-labels?
       [article-labels-view article-id :self-only? private-view?])]))
