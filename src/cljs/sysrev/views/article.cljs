(ns sysrev.views.article
  (:require [clojure.string :as str]
            goog.object
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.annotation :as annotation]
            [sysrev.pdf :as pdf]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.components.core :as ui]
            [sysrev.views.keywords :refer [render-keywords render-abstract]]
            [sysrev.views.labels :refer [ArticleLabelsView]]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in? css filter-values]]
            [sysrev.macros :refer-macros [with-loader]]))

#_
(def-data :article/annotations
  :loaded?
  (fn [db project-id article-id _]
    (-> (get-in db [:data :project project-id :annotations])
        (contains? article-id)))
  :uri (fn [project-id article-id _]
         (str "/api/annotations/" article-id))
  :content (fn [project-id _ _] {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id article-id _] {:keys [annotations]}]
    {:db (assoc-in db [:data :project project-id :annotations article-id]
                   (or annotations []))}))

#_
(reg-sub
 ::article-annotations
 (fn [[_ _ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ article-id _]]
   (get-in project [:annotations article-id])))

#_
(defn slug-string->sentence-string
  "Convert a slug string into a normal English sentence.
  ex: gene_or_gene_product -> Gene or gene product"
  [string]
  (-> string (str/replace #"_" " ")))

;; this is for non-user-created annotations
#_
(defn process-annotations
  [raw-annotations]
  (->> raw-annotations
       ;; filter out everything but reach
       (filterv #(= "reach" (:ontology %)))
       ;; process into an overlay
       (mapv #(-> % (assoc :word (:name %)
                           :annotation (str (slug-string->sentence-string (:semantic_class %))))))
       ;; remove duplicates
       (group-by #(str/lower-case (:word %)))
       vals
       (map first)))

#_
(defn get-annotations
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

(defn- display-author-names [nmax authors]
  (str (str/join ", " (take nmax authors))
       (when (> (count authors) nmax) " et al")))

(defn ArticleScoreLabel [score]
  (when (some-> score pos?)
    [:div.ui.label.article-score "Prediction"
     [:div.detail
      [:span [:i {:class (css "grey" [(> score 0.5) "plus" :else "minus"] "circle icon")}]
       (str (-> score (* 100) (+ 0.5) int) "%")]]]))

(defn- ReviewStatusLabel [status]
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

(defn WithProjectSourceTooltip [source-id element]
  (let [{:keys [article-count]} @(subscribe [:project/sources source-id])
        source-info (some-> @(subscribe [:source/display-info source-id])
                            (sutil/string-ellipsis 150 "[.....]"))
        has-info? (not (empty? source-info))]
    [ui/FixedTooltipElement element
     [:div
      [:h5.ui.header {:class (css [has-info? "dividing"])}
       @(subscribe [:source/display-type source-id])
       (str " - " (or article-count "?") " articles")]
      (when has-info? [:p source-info])]
     "25em"
     :delay 150]))

(defn SourceLinkButton [source-id text]
  [WithProjectSourceTooltip source-id
   [:div.ui.tiny.button {:on-click (util/wrap-user-event
                                    #(dispatch [:articles/load-source-filters [source-id]]))}
    text]])

(defn ArticleSourceLinks [article-id]
  (let [source-ids @(subscribe [:article/sources article-id])]
    (when (not-empty source-ids)
      [:div.ui.small.left.aligned.form.article-source-links
       [:div.left.aligned.field
        [:label "Sources"]
        [:div (doall (map-indexed (fn [i source-id] ^{:key source-id}
                                    [SourceLinkButton source-id (str (inc i))])
                                  source-ids))]]])))

(defn filter-annotations-by-field [annotations client-field text]
  (filter-values #(let [{:keys [context]} %
                        {:keys [text-context]} context]
                    (or (and (string? text-context) (= (:client-field context) client-field))
                        (= (:field text-context) client-field)
                        (= text-context text)))
                 annotations))

(defn ArticleAnnotatedField [article-id field-name text & {:keys [reader-error-render]}]
  (let [project-id @(subscribe [:active-project-id])
        self-id @(subscribe [:self/user-id])
        on-review? @(subscribe [:review/on-review-task?])
        ann-context {:class "abstract" :project-id project-id :article-id article-id}
        annotations (filter-annotations-by-field
                     (if (and self-id on-review?)
                       @(subscribe [:annotator/user-annotations ann-context self-id])
                       @(subscribe [:annotator/all-annotations ann-context true]))
                     field-name text)]
    [annotator/AnnotationCapture ann-context field-name
     [annotation/AnnotatedText (vals annotations) text
      :reader-error-render reader-error-render
      :field field-name]]))

(defn ArticleInfoMain [article-id & {:keys [context]}]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:article project-id article-id]] {}
      (let [authors @(subscribe [:article/authors article-id])
            journal-name @(subscribe [:article/journal-name article-id])
            title @(subscribe [:article/title article-id])
            title-render @(subscribe [:article/title-render article-id])
            journal-render @(subscribe [:article/journal-render article-id])
            abstract @(subscribe [:article/abstract article-id])
            urls @(subscribe [:article/urls article-id])
            documents @(subscribe [:article/documents article-id])
            date @(subscribe [:article/date article-id])
            pdfs @(subscribe [:article/pdfs article-id])
            [pdf] pdfs
            #_ annotations-raw #_ @(subscribe [::article-annotations article-id])
            #_ annotations #_ (condp = context
                                :article-list
                                (process-annotations annotations-raw)
                                :review
                                (->> @(subscribe [:project/keywords])
                                     vals
                                     (mapv :value)
                                     (mapv #(hash-map :word %))))
            ann-context {:class "abstract" :project-id project-id :article-id article-id}
            annotator? (= :annotations @(subscribe [:review-interface]))
            pdf-url (pdf/view-s3-pdf-url project-id article-id (:key pdf) (:filename pdf))
            visible-url (if (and pdf (empty? abstract))
                          pdf-url
                          @(subscribe [:view-field :article [article-id :pdf-url]]))
            pdf-only? (and title visible-url (:filename pdf)
                           (= (str/trim title) (str/trim (:filename pdf))))]
        ;;(get-annotations article-id)
        [:div {:data-article-id article-id}
         (when (and (not-empty pdfs) (not-empty abstract))
           [:div {:style {:margin-bottom "0.5em"}}
            [ui/tabbed-panel-menu
             [{:tab-id :abstract
               :content "Abstract"
               :action #(dispatch [:set-view-field :article [article-id :pdf-url] nil])}
              {:tab-id :pdf
               :content "PDF"
               :action #(dispatch [:set-view-field :article [article-id :pdf-url] pdf-url])}]
             (if (nil? visible-url) :abstract :pdf)
             "article-content-tab"]])
         [:h3.header {:style {:margin-top "0px"}}
          (let [render-title-keywords (fn [] [render-keywords article-id title-render
                                              {:label-class "large"}])]
            (when-not (or pdf-only? (empty? title))
              (if annotator?
                [ArticleAnnotatedField article-id "primary-title" title
                 :reader-error-render [render-title-keywords]]
                [render-title-keywords])))]
         (when-not (or pdf-only? (empty? journal-name))
           [:h3.header {:style {:margin-top "0px"}}
            [render-keywords article-id journal-render {:label-class "large"}]])
         (when-not (or pdf-only? (empty? date))
           [:h5.header {:style {:margin-top "0px"}} date])
         (when-not (or pdf-only? (empty? authors))
           [:h5.header {:style {:margin-top "0px"}} (display-author-names 5 authors)])
         (if visible-url
           [pdf/ViewPDF {:pdf-url visible-url :entry pdf}]
           (when-not (empty? abstract)
             (if annotator?
               [ArticleAnnotatedField article-id "abstract" abstract
                :reader-error-render [render-abstract article-id]]
               [render-abstract article-id])))
         [:div.ui.grid.article-links
          [:div.twelve.wide.left.aligned.middle.aligned.column
           (when-not (empty? documents)
             [:div.ui.content.horizontal.list
              {:style {:padding-top "0.75em"}}
              (doall (map-indexed (fn [i {:keys [fs-path url]}] ^{:key [i]}
                                    [ui/document-link url fs-path])
                                  documents))])
           (when-not (empty? urls)
             [:div.ui.content.horizontal.list
              {:style {:padding-top "0.75em"}}
              (doall (map-indexed (fn [i url] ^{:key [i]} [ui/out-link url])
                                  urls))])]
          [:div.four.wide.right.aligned.middle.aligned.column
           [ArticleSourceLinks article-id]]]]))))

(def flag-display-text {"user-duplicate"   "Duplicate article (exclude)"
                        "user-conference"  "Conference abstract (exclude)"})

(defn- ArticleFlagLabel [flag-name]
  (when-let [text (get flag-display-text flag-name)]
    [:div.ui.left.labeled.button.article-flag {:key flag-name}
     [:div.ui.basic.label [:i.flag.icon {:style {:margin "0" :padding "0 0.25em"}}]]
     [:div.ui.small.orange.basic.button text]]))

(defn- ArticleFlagsView [article-id & [wrapper-class]]
  (when-let [flag-names (seq (->> (keys @(subscribe [:article/flags article-id]))
                                  (filter #(get flag-display-text %))
                                  sort))]
    [:div {:class wrapper-class}
     (doall (map-indexed (fn [i flag-name] ^{:key i}
                           [ArticleFlagLabel flag-name])
                         flag-names))]))

(defn ArticleDuplicatesSegment [article-id]
  (when-let [duplicates @(subscribe [:article/duplicates article-id])]
    [:div.ui.segment {:key [:article-duplicates]}
     [:h5 "Duplicate articles:"
      (doall (for [article-id (:article-ids duplicates)]
               [:span {:key article-id}
                nbsp nbsp [:a {:href (project-uri nil (str "/articles/" article-id))}
                           (str "#" article-id)]]))]]))

(defn ArticleInfo
  [article-id & {:keys [show-labels? private-view? show-score? context change-labels-button]
                 :or {show-score? true}}]
  (let [full-size? (util/full-size?)
        project-id @(subscribe [:active-project-id])
        status @(subscribe [:article/review-status article-id])
        score @(subscribe [:article/score article-id])
        ann-context {:class "abstract" :project-id project-id :article-id article-id}
        {:keys [unlimited-reviews]} @(subscribe [:project/settings])
        {:keys [disabled?] :as duplicates} @(subscribe [:article/duplicates article-id])]
    [:div.article-info-top
     (dispatch [:require (annotator/annotator-data-item ann-context)])
     (dispatch [:require [:annotator/status project-id]])
     (with-loader [[:article project-id article-id]]
       {:class "ui segments article-info"}
       (list [:div.ui.middle.aligned.header.grid.segment.article-header {:key :article-header}
              [:div.five.wide.middle.aligned.column>h4.ui.article-info "Article Info"]
              [:div.eleven.wide.column.right.aligned
               (when disabled?
                 [:div.ui.basic.label.review-status.orange "Disabled"])
               (when (and score show-score? (not= status :single))
                 [ArticleScoreLabel score])
               (when-not (and (= context :review) (true? unlimited-reviews))
                 [ReviewStatusLabel (if private-view? :user status)])]]

             (when duplicates ^{:key :duplicates}
               [ArticleDuplicatesSegment article-id])

             (when-not full-size? ^{:key :article-flags}
               [ArticleFlagsView article-id "ui segment"])

             [:div.ui.segment.article-content {:key :article-content}
              [ArticleInfoMain article-id :context context]]

             ^{:key :article-pdfs} [pdf/PDFs article-id]))
     (when change-labels-button [change-labels-button])
     (when show-labels? [ArticleLabelsView article-id :self-only? private-view?])]))
