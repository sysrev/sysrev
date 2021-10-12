(ns sysrev.views.article
  (:require ["react-json-view" :default ReactJson]
            ["react-markdown" :as ReactMarkdown]
            ["react-xml-viewer" :as XMLViewer]
            ["remark-gfm" :as gfm]
            ["xml2js" :as xml2js]
            [clojure.string :as str]
            goog.object
            [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [sysrev.shared.labels :refer [predictable-label-types]]
            [sysrev.data.cursors :refer [map-from-cursors]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.annotation :as annotation]
            [sysrev.pdf :as pdf]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.components.core :as ui]
            [sysrev.views.keywords :refer [render-keywords render-abstract]]
            [sysrev.views.labels :refer [ArticleLabelsView]]
            [sysrev.views.reagent-json-view :refer [ReactJSONView]]
            [sysrev.views.semantic :refer [Checkbox]]
            [sysrev.util :as util :refer [css filter-values nbsp format]]
            [sysrev.macros :refer-macros [with-loader]]
            [sysrev.shared.components :as shared :refer [colors]]))

(def XMLViewerComponent (r/adapt-react-class XMLViewer))

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

(defn- ArticleScoreLabel [score]
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

(defn- WithProjectSourceTooltip [source-id element]
  (let [{:keys [article-count]} @(subscribe [:project/sources source-id])
        source-info (some-> @(subscribe [:source/display-info source-id])
                            (util/ellipsis-middle 150 "[.....]"))]
    [ui/Tooltip
     {:style {:min-width "25em"}
      :mouse-enter-delay 150
      :trigger element
      :tooltip [:div
                [:h5.ui.header {:class (css [(seq source-info) "dividing"])}
                 @(subscribe [:source/display-type source-id])
                 (str " - " (or article-count "?") " articles")]
                (when (seq source-info) [:p source-info])]}]))

(defn- SourceLinkButton [source-id text]
  [WithProjectSourceTooltip source-id
   [:div.ui.tiny.button {:on-click (util/wrap-user-event
                                    #(dispatch [:articles/load-source-filters [source-id]]))}
    text]])

(defn- ArticleSourceLinks [article-id]
  (let [source-ids @(subscribe [:article/sources article-id])]
    (when (not-empty source-ids)
      [:div.ui.small.left.aligned.form.article-source-links
       [:div.left.aligned.field
        [:label "Sources"]
        [:div (doall (map-indexed (fn [i source-id] ^{:key source-id}
                                    [SourceLinkButton source-id (str (inc i))])
                                  source-ids))]]])))

(defn- filter-annotations-by-field [annotations client-field text]
  (filter-values #(let [{:keys [context]} %
                        {:keys [text-context]} context]
                    (or (and (string? text-context) (= (:client-field context) client-field))
                        (= (:field text-context) client-field)
                        (= text-context text)))
                 annotations))

(defn- ArticleAnnotatedField [article-id field-name text & {:keys [reader-error-render]}]
  (let [project-id @(subscribe [:active-project-id])
        ann-context {:class "abstract" :project-id project-id :article-id article-id}
        annotations
        (filter-annotations-by-field
                    @(subscribe [:annotator/label-annotations ann-context])
                     ;; [LEGACY ANNOTATIONS]
                     ;; #_(if (and self-id on-review?)
                     ;;   @(subscribe [:annotator/user-annotations ann-context self-id])
                     ;;   @(subscribe [:annotator/all-annotations ann-context true]))
                     field-name text)]
    [annotator/AnnotationCapture ann-context field-name
     [annotation/AnnotatedText (vals annotations) text
      :reader-error-render reader-error-render
      :field field-name]]))

(defn- ArticleInfoMain [article-id & {:keys [context]}]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:article project-id article-id]] {}
      (let [authors @(subscribe [:article/authors article-id])
            journal-name @(subscribe [:article/journal-name article-id])
            title @(subscribe [:article/title article-id])
            title-render @(subscribe [:article/title-render article-id])
            journal-render @(subscribe [:article/journal-render article-id])
            abstract @(subscribe [:article/abstract article-id])
            urls @(subscribe [:article/urls article-id])
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
            annotator? (= :annotations @(subscribe [:review-interface]))
            pdf-url (pdf/view-s3-pdf-url project-id article-id (:key pdf) (:filename pdf))
            visible-url (if (and pdf (empty? abstract))
                          pdf-url
                          @(subscribe [:view-field :article [article-id :pdf-url]]))
            pdf-only? (and title visible-url (:filename pdf)
                           (= (str/trim title) (str/trim (:filename pdf))))]
        ;;(get-annotations article-id)
        [:div
         ;; abstract / pdf selection
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
         ;; title render
         [:h3.header {:style {:margin-top "0px"}}
          (let [render-title-keywords (fn [] [render-keywords article-id title-render
                                              {:label-class "large"}])]
            (when-not (or pdf-only? (empty? title))
              (if annotator?
                [ArticleAnnotatedField article-id "primary-title" title
                 :reader-error-render [render-title-keywords]]
                [render-title-keywords])))]
         ;; render keywords
         (when-not (or pdf-only? (empty? journal-name))
           [:h3.header {:style {:margin-top "0px"}}
            [render-keywords article-id journal-render {:label-class "large"}]])
         ;; date
         (when-not (or pdf-only? (empty? date))
           [:h5.header {:style {:margin-top "0px"}} date])
         ;; authors
         (when-not (or pdf-only? (empty? authors))
           [:h5.header {:style {:margin-top "0px"}} (display-author-names 5 authors)])
         ;; show pdf
         (if visible-url
           [pdf/ViewReactPDF {:url visible-url :filename (:filename pdf)}]
           (when (seq abstract)
             (if annotator?
               [ArticleAnnotatedField article-id "abstract" abstract
                :reader-error-render [render-abstract article-id]]
               [render-abstract article-id])))
         ;; article links
         [:div.ui.grid.article-links {:style {:margin "0"}}
          [:div.twelve.wide.left.aligned.middle.aligned.column
           {:style {:margin "0" :padding "0"}}
           (when (seq urls)
             [:div.ui.content.horizontal.list
              {:style {:padding-top "0.75em"}}
              (doall (map-indexed (fn [i url] ^{:key [i]} [ui/OutLink url])
                                  urls))])]
          [:div.four.wide.right.aligned.middle.aligned.column
           {:style {:margin "0" :padding "0"}}
           [ArticleSourceLinks article-id]]]]))))

(defonce checked? (r/atom false))

(defn Entity [article-id]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:article project-id article-id]] {}
      (let [{:keys [metadata]} @(subscribe [:article/raw article-id])
            mimetype @(subscribe [:article/mimetype article-id])
            json (r/atom {})
            content @(subscribe [:article/content article-id])
            title article-id
            source-id (first @(subscribe [:article/sources article-id]))
            cursors (mapv #(mapv keyword %)
                          (get-in @(subscribe [:project/sources source-id])
                                  [:meta :cursors]))]
        [:div {:id article-id}
         [:h2 title]
         [:br]
         [:div {:id "content"}
          (condp = mimetype
            "application/xml"
            (do (xml2js/parseString content (fn [_err result]
                                              (reset! json result)))
                [:div [Checkbox {:as "h4"
                                 :checked @checked?
                                 :on-change #(do (reset! checked? (not @checked?)))
                                 :toggle true
                                 :label "Switch Views"}]
                 (if @checked?
                   [:div {:style {:white-space "normal" :overflow "overlay"}}
                    [XMLViewerComponent {:xml content}]]
                   [ReactJSONView {:json @json}])])
            "application/json"
            (do (reset! json (.parse js/JSON content))
                [ReactJSONView {:json (if (seq cursors)
                                        (map-from-cursors
                                         (js->clj @json :keywordize-keys true) cursors)
                                        @json)}])
            "application/pdf"
            [:div [pdf/ViewBase64PDF {:content content}]]
            ;; default
            content)]
         (when (seq metadata)
           [:div.article-metadata
            [ReactJSONView {:json metadata}]])]))))

(defn CTDocument [article-id]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:article project-id article-id]] {}
      (let [json (let [ct-json @(subscribe [:article/json article-id])]
                   {:ProtocolSection (:ProtocolSection ct-json)
                    :DerivedSection (:DerivedSection ct-json)})
            nctid (get-in json [:ProtocolSection :IdentificationModule :NCTId])
            title (get-in json [:ProtocolSection :IdentificationModule :BriefTitle])
            source-id (first @(subscribe [:article/sources article-id]))
            cursors (mapv #(mapv keyword %)
                          (get-in @(subscribe [:project/sources source-id])
                                  [:meta :cursors]))]
        [:div {:id nctid}
         [:h2 title]
         [ui/OutLink (str "https://clinicaltrials.gov/ct2/show/" nctid)]
         [:br]
         [:> ReactJson
          {:display-array-key false
           :display-data-types false
           :name nil
           :quotes-on-keys false
           :src
           (if (seq cursors)
             (clj->js (map-from-cursors json cursors))
             (clj->js json))
           :theme (if @(subscribe [:self/dark-theme?])
                    "monokai"
                    "rjv-default")}]]))))

(defn FDADrugsDocs [article-id]
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:article project-id article-id]] {}
      (let [{:keys [datapub primary-title]}
            #__ @(subscribe [:article/raw article-id])
            {:keys [content metadata] :as entity}
            #__ @(subscribe [:datapub/entity (:entity-id datapub)])
            source-id (first @(subscribe [:article/sources article-id]))
            cursors (mapv #(mapv keyword %)
                          (get-in @(subscribe [:project/sources source-id])
                                  [:meta :cursors]))]
        (dispatch [:require [:datapub-entity (:entity-id datapub)]])
        (when entity
          [:div
           [:h2 primary-title]
           [ui/OutLink (:ApplicationDocsURL metadata)]
           [:br]
           [:div [pdf/ViewBase64PDF {:content content}]]
           [:br]
           [:> ReactJson
            {:display-array-key false
             :display-data-types false
             :name nil
             :quotes-on-keys false
             :src
             (if (seq cursors)
               (clj->js (map-from-cursors metadata cursors))
               (clj->js metadata))
             :theme (if @(subscribe [:self/dark-theme?])
                      "monokai"
                      "rjv-default")}]])))))

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

(defn- ArticleDuplicatesSegment [article-id]
  (when-let [duplicates @(subscribe [:article/duplicates article-id])]
    [:div.ui.segment {:key [:article-duplicates]}
     [:h5 "Duplicate articles:"
      (doall (for [article-id (:article-ids duplicates)]
               [:span {:key article-id}
                nbsp nbsp [:a {:href (project-uri nil (str "/articles/" article-id))}
                           (str "#" article-id)]]))]]))

(defn ArticlePredictions [article-id]
  (let [label->type #(deref (subscribe [:label/value-type "na" (:label-id %)]))
        labels (->> @(subscribe [:project/labels-raw])
                    vals
                    (filter #(contains? predictable-label-types (label->type %)))
                    (filter #(:enabled %))
                    (sort-by #(count (get-in % [:definition :all-values]))))
        predictions @(subscribe [:article/predictions article-id])
        columns [:label :label-type :value :probability]
        render-prob (fn [prob]
                      (let [color (cond (< prob 0.4)   :red
                                        (< prob 0.45)  :pink
                                        (> prob 0.6)   :bright-green
                                        (> prob 0.55)  :green
                                        :else          nil)]
                        [:span (when color {:style {:color (get colors color)}})
                         (format "%.1f%%" (* 100 prob))]))
        rows (->> labels
                  (mapcat (fn [label]
                            (let [pred-values (get-in predictions [(:label-id label)])]
                              (mapv (fn [[value pred]]
                                      [{:label (:short-label label)
                                        :value value
                                        :label-type (label->type label)
                                        :probability (render-prob pred)}])
                                    pred-values))))
                  flatten
                  (filterv (fn [row] (not (and (= "FALSE" (:value row))
                                               (= "boolean" (:label-type row)))))))]
    (shared/table columns rows
                  :header "Predictions"
                  :props {:id "predictions"})))

(defn ArticleInfo [article-id & {:keys [show-labels? private-view? show-score? context
                                        change-labels-button resolving?]
                                 :or {show-score? true}}]
  (let [full-size? (util/full-size?)
        project-id @(subscribe [:active-project-id])
        {:keys [types]} @(subscribe [:article/raw article-id])
        status @(subscribe [:article/review-status article-id])
        score @(subscribe [:article/score article-id])
        datasource-name @(subscribe [:article/datasource-name article-id])
        helper-text @(subscribe [:article/helper-text article-id])
        ann-context {:class "abstract" :project-id project-id :article-id article-id}
        {:keys [unlimited-reviews]} @(subscribe [:project/settings])
        {:keys [disabled?] :as duplicates} @(subscribe [:article/duplicates article-id])]
    [:div.article-info-top
     (dispatch [:require (annotator/annotator-data-item ann-context)])
     (dispatch [:require [:annotator/status project-id]])
     (with-loader [[:article project-id article-id]]
       {:class "ui segments article-info"}
       (list [:div.ui.middle.aligned.header.grid.segment.article-header {:key :article-header}
              [:div.five.wide.middle.aligned.column>h4.ui.article-info
               {:data-article-id article-id} "Article Info"]
              [:div.eleven.wide.column.right.aligned
               [:a.ui.tiny.button {;; prevent pushy from intercepting href click event
                                   :data-pushy-ignore true
                                   :href (util/url-hash "predictions")
                                   :style {:padding ".5833em .833em"}}
                "See Predictions"]
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
              ;; if adding new datasource, be sure to disable annotator
              ;; in sysrev.views.main/SidebarColumn
              ;; datasource-name isn't coming through for some, so we
              ;; work around by checking :types
              (condp = (if types
                         [(:article-type types) (:article-subtype types)]
                         datasource-name)
                "ctgov"  [CTDocument article-id]
                "entity" [Entity article-id]
                ["pdf" "fda-drugs-docs"] [FDADrugsDocs article-id]
                [ArticleInfoMain article-id :context context])]
             (when-not (= datasource-name "entity")
               ^{:key :article-pdfs} [pdf/ArticlePdfListFull article-id])))
     (when helper-text
       [:div
        [:h3 "Help info"]
        [:> ReactMarkdown {:plugins [gfm] :children helper-text}]])
     (when change-labels-button [change-labels-button])
     (when show-labels? [ArticleLabelsView article-id
                         :self-only? private-view? :resolving? resolving?])]))
