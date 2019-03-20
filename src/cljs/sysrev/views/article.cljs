(ns sysrev.views.article
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            goog.object
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.annotation :as annotation]
            [sysrev.pdf :as pdf]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.components :as ui]
            [sysrev.views.keywords :refer [render-keywords render-abstract]]
            [sysrev.views.labels :refer [article-labels-view]]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in?]])
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

#_(defn get-annotations
  "Get annotations with a delay of seconds"
  [article-id & {:keys [delay]
                 :or {delay 3}}]
  (let [project-id @(subscribe [:active-project-id])
        article-id-state (subscribe [:visible-article-id])]
    (js/setTimeout
     (fn [_]
       (when (= article-id @article-id-state)
         (dispatch [:require [:article/annotations project-id article-id]])))
     (* delay 1000))))

(defn annotations-filter [annotations client-field text]
  (let [on-review? (= @(subscribe [:active-panel]) [:project :review])
        self-id @(subscribe [:self/user-id])]
    (cond->> annotations
      true
      (filter #(let [text-context (get-in % [:context :text-context])]
                 (or (and (= (type text-context) (type "string"))
                          (= (get-in % [:context :client-field]) client-field))
                     (= (:field text-context) client-field)
                     (= text-context text))))
      on-review? (filter #(or (= (:user-id %) self-id)
                              (re-matches #"new-ann.*" (str (:id %))))))))

(defn WithProjectSourceTooltip [source-id element]
  [ui/FixedTooltipElement
   element
   (let [{:keys [article-count]}
         @(subscribe [:project/sources source-id])
         source-info (some-> @(subscribe [:source/display-info source-id])
                             (sutil/string-ellipsis 150 "[.....]"))
         has-info? (not (empty? source-info))]
     [:div
      [:h5.ui.header
       {:class (when has-info? "dividing")}
       @(subscribe [:source/display-type source-id])
       " - "
       (str (or article-count "?"))
       " articles"]
      (when has-info?
        [:p source-info])])
   "25em"
   :delay 150])

(defn SourceLinkButton [source-id text]
  [WithProjectSourceTooltip source-id
   [:div.ui.tiny.button
    {:on-click (util/wrap-user-event
                #(dispatch [:articles/load-source-filters [source-id]]))}
    text]])

(defn ArticleSourceLinks [article-id]
  (let [source-ids @(subscribe [:article/sources article-id])]
    (when (not-empty source-ids)
      [:div.ui.small.left.aligned.form.article-source-links
       [:div.left.aligned.field
        [:label "Sources"]
        [:div
         (doall
          (map-indexed
           (fn [i source-id]
             ^{:key {:source-id source-id}}
             [SourceLinkButton source-id (str (inc i))])
           source-ids))]]])))

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
            pdfs @(subscribe [:article/pdfs article-id])
            on-review? (= @(subscribe [:active-panel]) [:project :review])
            self-id @(subscribe [:self/user-id])
            ;; annotations-raw @(subscribe [::article-annotations article-id])
            ;; annotations (condp = context
            ;;               :article-list
            ;;               (process-annotations annotations-raw)
            ;;               :review
            ;;               (->> @(subscribe [:project/keywords])
            ;;                    vals
            ;;                    (mapv :value)
            ;;                    (mapv #(hash-map :word %))))
            annotator-context
            {:class "abstract"
             :project-id project-id
             :article-id article-id}
            annotator-enabled?
            (and @(subscribe [:annotator/enabled annotator-context])
                 (= :annotations @(subscribe [:review-interface])))
            {:keys [key filename] :as entry} (first pdfs)
            pdf-url (pdf/view-s3-pdf-url
                     project-id article-id key filename)
            visible-url (if (and (not-empty pdfs) (empty? abstract))
                          pdf-url
                          @(subscribe [:view-field :article [article-id :visible-pdf]]))
            pdf-only? (and title visible-url filename
                           (= (str/trim title) (str/trim filename)))]
        ;;(get-annotations article-id)
        [:div {:data-article-id article-id}
         (when (and (not-empty pdfs) (not-empty abstract))
           [:div {:style {:margin-bottom "0.5em"}}
            [ui/tabbed-panel-menu
             [{:tab-id :abstract
               :content "Abstract"
               :action #(dispatch [:set-view-field :article
                                   [article-id :visible-pdf] nil])}
              {:tab-id :pdf
               :content "PDF"
               :action #(dispatch [:set-view-field :article
                                   [article-id :visible-pdf] pdf-url])}]
             (if (nil? visible-url) :abstract :pdf)
             "article-content-tab"]])
         [:h3.header {:style {:margin-top "0px"}}
          (when-not (or pdf-only? (empty? title))
            (if-not annotator-enabled?
              [render-keywords
               article-id @(subscribe [:article/title-render article-id])
               {:label-class "large"}]
              ;; refactor so that AnnotationCapture and AnnotatedText are simply given an atom
              ;; containing the annotations that are relevant to them
              [annotator/AnnotationCapture
               annotator-context
               "primary-title"
               (let [saved-annotations (or (vals @(subscribe [:annotator/article project-id article-id]))
                                           '())
                     unsaved-annotation (-> @(subscribe [:sysrev.views.annotator/get
                                                         {:class "abstract" :project-id project-id :article-id article-id}])
                                            :new-annotation)
                     annotations (annotations-filter (conj saved-annotations unsaved-annotation) "primary-title" title)]
                 [annotation/AnnotatedText
                  annotations
                  title
                  :reader-error-render [render-keywords article-id @(subscribe [:article/title-render article-id])]
                  :field "primary-title"])]))]
         (when-not (or pdf-only? (empty? journal-name))
           [:h3.header {:style {:margin-top "0px"}}
            [render-keywords article-id journal-render
             {:label-class "large"}]])
         (when-not (or pdf-only? (empty? date))
           [:h5.header {:style {:margin-top "0px"}}
            date])
         (when-not (or pdf-only? (empty? authors))
           [:h5.header {:style {:margin-top "0px"}}
            (author-names-text 5 authors)])
         (if visible-url
           [pdf/ViewPDF {:pdf-url visible-url :entry entry}]
           ;; abstract, with annotations
           (when-not (empty? abstract)
             (if-not annotator-enabled?
               [render-abstract article-id]
               [annotator/AnnotationCapture
                annotator-context
                ;; todo: consolidate class names that need to be passed
                ;;       to both AnnotationCapture and AnnotatedText
                "abstract"
                (let [saved-annotations (or (vals @(subscribe [:annotator/article project-id article-id]))
                                            '())
                      unsaved-annotation (-> @(subscribe [:sysrev.views.annotator/get
                                                          {:class "abstract" :project-id project-id :article-id article-id}])
                                             :new-annotation)
                      annotations (annotations-filter (conj saved-annotations unsaved-annotation) "abstract" abstract)]
                  [annotation/AnnotatedText
                   ;; todo: refactor so that unsaved-annotations and saved-annotation are saved in one location
                   annotations
                   abstract
                   :reader-error-render [render-abstract article-id]
                   :field "abstract"])])))
         [:div.ui.grid.article-links
          [:div.twelve.wide.left.aligned.middle.aligned.column
           (when-not (empty? documents)
             [:div.ui.content.horizontal.list
              {:style {:padding-top "0.75em"}}
              (doall
               (map-indexed (fn [idx {:keys [fs-path url]}]
                              ^{:key [idx]} [ui/document-link url fs-path])
                            documents))])
           (when-not (empty? urls)
             [:div.ui.content.horizontal.list
              {:style {:padding-top "0.75em"}}
              (doall
               (map-indexed (fn [idx url]
                              ^{:key [idx]} [ui/out-link url])
                            urls))])]
          [:div.four.wide.right.aligned.middle.aligned.column
           [ArticleSourceLinks article-id]]]]))))

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

(defn ArticleInfo
  [article-id & {:keys [show-labels? private-view? show-score? context]
                 :or {show-score? true}}]
  (let [project-id @(subscribe [:active-project-id])
        status @(subscribe [:article/review-status article-id])
        full-size? (util/full-size?)
        score @(subscribe [:article/score article-id])
        duplicates @(subscribe [:article/duplicates article-id])
        annotator-context {:class "abstract"
                           :project-id project-id
                           :article-id article-id}
        {:keys [unlimited-reviews]} @(subscribe [:project/settings])]
    (dispatch [:require (annotator/annotator-data-item annotator-context)])
    [:div.article-info-top
     (with-loader [[:article project-id article-id]]
       {:class "ui segments article-info"}
       (list
        [:div.ui.middle.aligned.header.grid.segment.article-header
         {:key [:article-header]}
         [:div.five.wide.middle.aligned.column
          [:h4.ui.article-info "Article Info"]]
         [:div.eleven.wide.column.right.aligned
          [annotator/AnnotationToggleButton
           annotator-context :class "mini"]
          (when (:disabled? duplicates)
            [article-disabled-label])
          (when (and score show-score? (not= status :single))
            [article-score-label score])
          (when-not (and (= context :review) (true? unlimited-reviews))
            [review-status-label (if private-view? :user status)])]]
        (article-duplicates-segment article-id)
        (when-not full-size? (article-flags-view article-id "ui segment"))
        [:div.ui.segment.article-content
         {:key [:article-content]}
         #_ [annotator/AnnotationMenu annotator-context "abstract"]
         [article-info-main-content article-id
          :context context]]
        ^{:key :article-pdfs}
        [pdf/PDFs article-id]))
     (when show-labels?
       [article-labels-view article-id :self-only? private-view?])]))
