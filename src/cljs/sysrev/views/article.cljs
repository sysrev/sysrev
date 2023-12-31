(ns sysrev.views.article
  (:require [clojure.string :as str]
            goog.object
            [medley.core :as medley]
            [re-frame.core :refer [dispatch reg-sub subscribe]]
            ["react-json-view" :default ReactJson]
            ["react-markdown" :as ReactMarkdown]
            ["react-xml-viewer" :as XMLViewer]
            [reagent.core :as r]
            ["remark-gfm" :as gfm]
            [sysrev.ajax :as ajax]
            [sysrev.annotation :as ann]
            [sysrev.data.cursors :refer [map-from-cursors]]
            [sysrev.datapub :as datapub]
            [sysrev.macros :refer-macros [with-loader]]
            [sysrev.pdf :as pdf]
            [sysrev.shared.components :as shared :refer [colors]]
            [sysrev.shared.labels :refer [predictable-label-types]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.util :as util :refer [css filter-values format nbsp]]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.components.brat :as brat]
            [sysrev.views.components.core :as ui]
            [sysrev.views.keywords :refer [render-abstract render-keywords]]
            [sysrev.views.labels :refer [ArticleLabelsView]]
            [sysrev.views.reagent-json-view :refer [ReactJSONView]]
            [sysrev.views.semantic :refer [Checkbox]]
            ["xml2js" :as xml2js]))

(def XMLViewerComponent (r/adapt-react-class XMLViewer))

(reg-sub ::pdf-annotations
         (fn [[_ article-id]]
           (subscribe [:article/labels article-id]))
         (fn [labels]
           (->> labels
                vals
                (mapcat vals)
                (keep (fn [{:keys [answer]}]
                        (when (map? answer)
                          (->> answer vals
                               (reduce
                                #(assoc
                                  %
                                  (:annotation-id %2)
                                  (select-keys %2 [:annotation-id :document-id :selection :xfdf]))
                                {})))))
                (reduce merge))))

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
         field-name text)]
    [annotator/AnnotationCapture ann-context field-name
     [ann/AnnotatedText (vals annotations) text
      :reader-error-render reader-error-render
      :field field-name]]))

(defn JSONView [content cursors]
  [:> ReactJson
   {:display-array-key false
    :display-data-types false
    :name nil
    :quotes-on-keys false
    :src
    (if (seq cursors)
      (clj->js (map-from-cursors content cursors))
      (clj->js content))
    :theme (if @(subscribe [:self/dark-theme?])
             "monokai"
             "rjv-default")}])

(defn PDFAnnotator [{:keys [article-id authorization document-id project-id url]}]
  (let [annotation-context {:article-id article-id
                            :class "abstract"
                            :project-id project-id}]
    [:div [annotator/AnnotatingPDFViewer
           {:annotation-context annotation-context
            :annotations (if @(subscribe [:review-interface])
                           (subscribe [:annotator/label-annotations annotation-context
                                       [:annotation-id :document-id :selection :xfdf]])
                           (subscribe [::pdf-annotations article-id]))
            :authorization authorization
            :document-id document-id
            :read-only? (not= :annotations @(subscribe [:review-interface]))
            :theme (if @(subscribe [:self/dark-theme?])
                     "dark" "light")
            :url url}]]))

(defn BratFrame [article-id]
  (let [{:keys [abstract]} @(subscribe [:article/raw article-id])]
    (when (seq abstract)
      [brat/Brat {:text abstract} (vals @(subscribe [:project/labels-raw])) article-id (r/atom true)])))

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
            annotator? (= :annotations @(subscribe [:review-interface]))
            pdf-url (pdf/view-s3-pdf-url project-id article-id (:key pdf) (:filename pdf))
            visible-url (if (and pdf (empty? abstract))
                          pdf-url
                          @(subscribe [:view-field :article [article-id :pdf-url]]))
            pdf-only? (and title visible-url (:filename pdf)
                           (= (str/trim title) (str/trim (:filename pdf))))]
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
         (cond
           visible-url [PDFAnnotator
                        {:article-id article-id
                         :document-id (:filename pdf)
                         :project-id project-id
                         :url visible-url}]
           (empty? abstract) nil
           annotator? [ArticleAnnotatedField article-id "abstract" abstract
                       :reader-error-render [render-abstract article-id]]
           (and (= context :review)
                (some #(= "relationship" (:value-type %)) (vals @(subscribe [:project/labels-raw]))))
           [BratFrame article-id]
           :else [render-abstract article-id])
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

(defn CTDocument []
  (let [state (r/atom {:current-version nil :versions nil})]
    (fn [article-id]
      (when-let [project-id @(subscribe [:active-project-id])]
        (with-loader [[:article project-id article-id]] {}
          (let [{:keys [current-version versions]} @state
                {:keys [datapub primary-title]}
                #__ @(subscribe [:article/raw article-id])
                {:keys [entity-id]} datapub
                version-entity-id (when current-version
                                    (get versions current-version))
                {:keys [externalId]} @(subscribe [:datapub/entity entity-id])
                {:keys [contentUrl] :as version-entity}
                #__ @(subscribe [:datapub/entity version-entity-id])
                content (when contentUrl
                          (or @(subscribe [:datapub/entity-content contentUrl])
                              (dispatch [:require [:datapub-entity-content contentUrl]])))
                source-id (first @(subscribe [:article/sources article-id]))
                cursors (mapv #(mapv keyword %)
                              (get-in @(subscribe [:project/sources source-id])
                                      [:meta :cursors]))
                version-entity-ids @(subscribe [:datapub/entities-for-external-id 1 externalId])]
            (when entity-id
              (dispatch [:require [:datapub-entity entity-id]]))
            (when version-entity-id
              (dispatch [:require [:datapub-entity version-entity-id]])
              (when (and version-entity (< 0 current-version))
                (dispatch [:require [:datapub-entity (get versions (dec current-version))]]))
              (when (and version-entity (< current-version (dec versions)))
                (dispatch [:require [:datapub-entity (get versions (inc current-version))]])))
            (when externalId
              (dispatch [:require [:datapub-entities-for-external-id 1 externalId]]))
            (when (not= versions version-entity-ids)
              (js/setTimeout
               #(swap! state assoc
                       :current-version (dec (count version-entity-ids))
                       :versions (vec version-entity-ids))))
            (when version-entity
              [:div
               [:div
                [:button {:class (if (and current-version (< 0 current-version))
                                   "ui primary button"
                                   "ui primary button disabled")
                          :on-click #(swap! state update :current-version dec)}
                 [:i.chevron.left.icon] "Previous"]
                [:button {:class (if (and current-version (< current-version (dec (count versions))))
                                   "ui primary button"
                                   "ui primary button disabled")
                          :on-click #(swap! state update :current-version inc)}
                 "Next" [:i.chevron.right.icon]]
                (when current-version
                  [:span {:style {:margin-left "2em"}}
                   "Version " (inc current-version) " of " (count versions)])]
               [:h2 primary-title]
               [:br]
               [ui/OutLink (str "https://clinicaltrials.gov/ct2/show/" externalId)]
               [:br]
               (when content
                 [JSONView content cursors])])))))))

(defn- AnystyleView [content]
  (let [{:strs [author container-title date pages url volume]} content
        authors (map (fn [{:strs [given family]}]
                       (cond
                         (and family given) (str family ", " given)
                         :else (or family given)))
                     author)]
    [:div
     (when (seq container-title)
       [:h3.header {:style {:margin-top "0px"}}
        (first container-title)
        (when (seq volume)
          (str ", vol. " (first volume)))
        (when (seq pages)
          (str ", pp. " (str/join ", " pages)))])
     ;; date
     (when (seq date)
       [:h5.header {:style {:margin-top "0px"}} (first date)])
     ;; authors
     (when (seq authors)
       [:h5.header {:style {:margin-top "0px"}} (display-author-names 5 authors)])
         ;; article links
     [:div.ui.grid.article-links {:style {:margin "0"}}
        [:div.twelve.wide.left.aligned.middle.aligned.column
         {:style {:margin "0" :padding "0"}}
         (when (seq url)
           [:div.ui.content.horizontal.list
            {:style {:padding-top "0.75em"}}
            (doall (map-indexed (fn [i url] ^{:key [i]} [ui/OutLink url])
                                url))])]]]))

(defn JSONEntity [{:keys [article-id datapub-auth entity project-id]}]
  (r/with-let [state (r/atom {:content (delay nil)})]
    (let [{:keys [contentUrl]} entity
          {:keys [title]} @(subscribe [:article/raw article-id])
          {:keys [content url]} @state]
      (when (not= url contentUrl)
        (js/setTimeout
         #(swap! state assoc
                 :url contentUrl
                 :content (ajax/rGET contentUrl
                                     {:headers {:Accept "application/json"
                                                :Authorization datapub-auth}}))
         0))
      [:div
       [:h2 title]
       [:br]
       (cond
         (= "anystyle" (get @content "sysrev:type")) [AnystyleView @content]
         @content [JSONView (js->clj @content)])])))

(defn PDFEntity [{:keys [article-id datapub-auth entity project-id]}]
  (let [{:keys [contentUrl id metadata]} entity
        {:keys [title]} @(subscribe [:article/raw article-id])]
    [:div
     [:h2 title]
     [:br]
     [PDFAnnotator
      {:article-id article-id
       :authorization datapub-auth
       :document-id id
       :project-id project-id
       :url contentUrl}]
     [:br]
     [JSONView (js/JSON.parse metadata)]]))

(defn XMLEntity [{:keys [article-id context]}]
  [ArticleInfoMain article-id :context context])

(def mediaType->fn
  {"application/json" JSONEntity
   "application/pdf" PDFEntity
   "application/xml" XMLEntity
   "text/xml" XMLEntity})

(defn DatasetEntityLoader [{:keys [article-id context dataset-jwt entity-id project-id]}]
  (r/with-let [state (r/atom {:entity (delay nil)})]
    (let [{:keys [entity]} @state
          {:keys [mediaType]} @entity
          renderer (mediaType->fn mediaType)]
      (when (not= entity-id (:entity-id @state))
        (js/setTimeout
         #(swap! state assoc
                 :entity-id entity-id
                 :entity (-> (datapub/dataset-entity "contentUrl mediaType metadata id")
                             (ajax/rGQL {:id entity-id}
                                        {:headers {:Authorization (str "Bearer " dataset-jwt)}})
                             (r/cursor [:data :datasetEntity])))
         0))
      (when @entity
        (if renderer
          [renderer
           {:article-id article-id
            :datapub-auth (str "Bearer " dataset-jwt)
            :entity @entity
            :project-id project-id}]
          [ArticleInfoMain article-id :context context])))))

(defn DatasetEntity [project-id article-id & {:keys [context]}]
  (let [{:keys [dataset-id external-id]} @(subscribe [:article/raw article-id])
        _ (dispatch [:require [:dataset-jwt dataset-id]])
        dataset-jwt @(subscribe [:datapub/dataset-jwt dataset-id])]
    (when (and dataset-jwt external-id)
      [DatasetEntityLoader
       {:article-id article-id
        :context context
        :dataset-jwt dataset-jwt
        :entity-id external-id
        :project-id project-id}])))

(defn FDADrugsDocs []
  (let [state (r/atom {:current-version nil :versions nil})]
    (fn [article-id]
      (when-let [project-id @(subscribe [:active-project-id])]
        (with-loader [[:article project-id article-id]] {}
          (let [{:keys [current-version versions]} @state
                {:keys [datapub primary-title]}
                #__ @(subscribe [:article/raw article-id])
                {:keys [entity-id]} datapub
                version-entity-id (when current-version
                                    (get versions current-version))
                {:keys [groupingId]} @(subscribe [:datapub/entity entity-id])
                {:keys [contentUrl metadata] :as version-entity}
                #__ @(subscribe [:datapub/entity version-entity-id])
                source-id (first @(subscribe [:article/sources article-id]))
                cursors (mapv #(mapv keyword %)
                              (get-in @(subscribe [:project/sources source-id])
                                      [:meta :cursors]))
                version-entity-ids @(subscribe [:datapub/entities-for-grouping-id 3 groupingId])]
            (when entity-id
              (dispatch [:require [:datapub-entity entity-id]]))
            (when version-entity-id
              (dispatch [:require [:datapub-entity version-entity-id]])
              (when (and version-entity (< 0 current-version))
                (dispatch [:require [:datapub-entity (get versions (dec current-version))]]))
              (when (and version-entity (< current-version (dec versions)))
                (dispatch [:require [:datapub-entity (get versions (inc current-version))]])))
            (when groupingId
              (dispatch [:require [:datapub-entities-for-grouping-id 3 groupingId]]))
            (when (not= versions version-entity-ids)
              (js/setTimeout
               #(swap! state assoc
                       :current-version (dec (count version-entity-ids))
                       :versions (vec version-entity-ids))))
            (when version-entity
              [:div
               [:div
                [:button {:class (if (and current-version (< 0 current-version))
                                   "ui primary button"
                                   "ui primary button disabled")
                          :on-click #(swap! state update :current-version dec)}
                 [:i.chevron.left.icon] "Previous"]
                [:button {:class (if (and current-version (< current-version (dec (count versions))))
                                   "ui primary button"
                                   "ui primary button disabled")
                          :on-click #(swap! state update :current-version inc)}
                 "Next" [:i.chevron.right.icon]]
                (when current-version
                  [:span {:style {:margin-left "2em"}}
                   "Version " (inc current-version) " of " (count versions)])]
               [:h2 primary-title]
               [:br]
               [ui/OutLink contentUrl "Download PDF"]
               [:br]
               [PDFAnnotator
                {:article-id article-id
                 :document-id version-entity-id
                 :project-id project-id
                 :url contentUrl}]
               [:br]
               [JSONView metadata cursors]])))))))

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

(defn- ChangeLabelsButton [article-id & {:keys [sidebar]}]
  (when-not @(subscribe [:review/on-review-task?])
    (let [editing?           @(subscribe [:review/editing? article-id])
          editing-allowed?   @(subscribe [:review/editing-allowed? article-id])
          resolving-allowed? @(subscribe [:review/resolving-allowed? article-id])]
      (when (not editing?)
        [:div.ui.fluid.left.labeled.icon.button.primary.change-labels
         {:class    (css [sidebar "small"] [resolving-allowed? "resolve-labels"])
          :style    {:margin-top "1em"}
          :on-click (util/wrap-user-event
                     #(do (dispatch [:review/enable-change-labels article-id])
                          (dispatch [:set-review-interface :labels])))}
         [:i.pencil.icon]
         (cond resolving-allowed? "Resolve Labels"
               editing-allowed?   "Change Labels"
               :else              "Manually Add Labels")]))))

(defn ArticleInfo [article-id & {:keys [show-labels? private-view? show-score? context resolving?]
                                 :or {show-score? true}}]
  (let [full-size? (util/full-size?)
        project-id @(subscribe [:active-project-id])
        {:keys [article-type types]} @(subscribe [:article/raw article-id])
        status @(subscribe [:article/review-status article-id])
        score @(subscribe [:article/score article-id])
        datasource-name @(subscribe [:article/datasource-name article-id])
        helper-text @(subscribe [:article/helper-text article-id])
        {:keys [unlimited-reviews]} @(subscribe [:project/settings])
        {:keys [disabled?] :as duplicates} @(subscribe [:article/duplicates article-id])]
    [:div.article-info-top
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

             (when duplicates
               ^{:key :duplicates}
               [ArticleDuplicatesSegment article-id])

             (when-not full-size?
               ^{:key :article-flags}
               [ArticleFlagsView article-id "ui segment"])

             [:div.ui.segment.article-content {:key :article-content}
              ;; if adding new datasource, be sure to disable annotator
              ;; in sysrev.views.main/SidebarColumn
              ;; datasource-name isn't coming through for some, so we
              ;; work around by checking :types
              (condp = (if types
                         [(:article-type types) (:article-subtype types)]
                         (or datasource-name article-type))
                "datapub" [DatasetEntity project-id article-id :context context]
                "entity" [Entity article-id]
                ["json" "ctgov"]  [CTDocument article-id]
                ["pdf" "fda-drugs-docs"] [FDADrugsDocs article-id]
                [ArticleInfoMain article-id :context context])]
             (when-not (= datasource-name "entity")
               ^{:key :article-pdfs} [pdf/ArticlePdfListFull article-id])))
     (when helper-text
       [:div
        [:h3 "Help info"]
        [:> ReactMarkdown {:plugins [gfm] :children helper-text}]])
     [ChangeLabelsButton article-id]
     (when show-labels? [ArticleLabelsView article-id
                         :self-only? private-view? :resolving? resolving?])]))
