(ns sysrev.views.panels.project.add-articles
  (:require [clojure.string :as str]
            [cljs-time.core :as t]
            [reagent.core :as r]
            [re-frame.core :refer
             [dispatch subscribe reg-sub reg-fx reg-event-fx trim-v]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.nav :as nav]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.pubmed :as pubmed]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.components.core :as ui]
            [sysrev.util :as util :refer [log format]]
            [sysrev.shared.util :as sutil :refer [in?]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

(setup-panel-state panel [:project :project :add-articles] {:state-var state})

(def initial-state {:read-only-message-closed? false})

(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(reg-event-fx
 :add-articles/reset-state!
 [trim-v]
 (fn [_]
   (reset! state initial-state)
   {}))

(def-action :sources/toggle-source
  :uri (fn [] "/api/toggle-source")
  :content (fn [project-id source-id enabled?]
             {:project-id project-id
              :source-id source-id
              :enabled? enabled?})
  :process
  (fn [_ [project-id _ _] {:keys [success] :as _result}]
    (when success
      {:dispatch-n
       (list [:reload [:review/task project-id]]
             [:reload [:project project-id]]
             [:reload [:project/sources project-id]])})))

(defn admin? []
  (or @(subscribe [:member/admin?])
      @(subscribe [:user/admin?])))

(defn article-or-articles
  "Return either the singular or plural form of article"
  [item-count]
  (sutil/pluralize item-count "article"))

(defn- source-import-timed-out? [source]
  (let [{:keys [meta source-id date-created
                article-count labeled-article-count]} source
        {:keys [importing-articles? deleting?]} meta]
    (and (true? importing-articles?)
         (t/within? {:start (t/epoch)
                     :end (t/minus (t/now) (t/minutes 30))}
                    date-created))))

(defn any-source-processing? []
  (or (loading/any-action-running? :only :sources/delete)
      (loading/any-action-running? :only :sources/toggle-source)
      (->> @(subscribe [:project/sources])
           (some (fn [source]
                   (and (or (-> source :meta :importing-articles? true?)
                            (-> source :meta :deleting? true?))
                        (not (source-import-timed-out? source)))))
           boolean)))

(defn ImportEndNoteView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.segment.import-upload
     [:h5
      "Upload an EndNote XML export file."]
     [:h5
      "To create from EndNote, go to File > Export,"
      " and under \"Save file as type\" select \"XML\"."]
     [ui/UploadButton
      (str "/api/import-articles/endnote-xml/" project-id)
      #(dispatch [:reload [:project/sources project-id]])
      "Upload XML File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))]]))

(defn ImportPMIDsView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.segment.import-upload
     [:h5
      "Upload a plain text file containing a list of PubMed IDs (one per line)."]
     [ui/UploadButton
      (str "/api/import-articles/pmid-file/" project-id)
      #(dispatch [:reload [:project/sources project-id]])
      "Upload Text File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))]]))

(defn ImportPDFZipsView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.segment.import-upload
     [:h5
      "Upload a zip file containing PDFs."
      " An article entry will be created for each PDF."]
     [ui/UploadButton
      (str "/api/import-articles/pdf-zip/" project-id)
      #(dispatch [:reload [:project/sources project-id]])
      "Upload Zip File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))]]))

(defn ImportRISView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.segment.import-upload
     [:h5
      "Upload a RIS file"]
     [ui/UploadButton
      (str "/api/import-articles/ris/" project-id)
      #(dispatch [:reload [:project/sources project-id]])
      "Upload RIS file..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))]]))

(defn DeleteArticleSource
  [source-id]
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.tiny.fluid.labeled.icon.button.delete-button
     {:class (when (any-source-processing?) "disabled")
      :on-click
      #(dispatch [:action [:sources/delete project-id source-id]])}
     "Delete"
     [:i.red.times.circle.icon]]))

(defn ToggleArticleSource
  [source-id enabled?]
  (let [project-id @(subscribe [:active-project-id])
        action [:sources/toggle-source
                project-id source-id (not enabled?)]
        loading? (loading/action-running? action)]
    [:button.ui.tiny.fluid.labeled.icon.button
     {:on-click #(dispatch [:action action])
      :class (cond
               loading?                 "loading"
               (any-source-processing?) "disabled")}
     (when-not loading?
       (if enabled?
         [:i.green.circle.icon]
         [:i.grey.circle.icon]))
     (if enabled?
       "Enabled"
       "Disabled")]))

;; TODO: these (:source meta) values should be stored as identifiers
;;       from an enforced set of possible values and shouldn't be
;;       mistakable for a description intended for users
(defn meta->source-name-vector
  [{:keys [source] :as meta}]
  (condp = source
    "PubMed search"
    ["PubMed Search" (str (:search-term meta))]

    "PMID file"
    ["PMIDs from File" (:filename meta)]

    "PMID vector"
    ["PMIDs from API" nil]

    "fact"
    ["PMIDs from FACTS" nil]

    "EndNote file"
    ["EndNote XML" (:filename meta)]

    "legacy"
    ["Legacy Import" nil]

    "PDF Zip file"
    ["PDF Zip File" (:filename meta)]

    [source nil]))

(reg-sub :source/display-type
         (fn [[_ source-id project-id]]
           (subscribe [:project/sources source-id project-id]))
         (fn [source]
           (let [stype (-> source :meta :source)]
             (condp = stype
               "PubMed search"   "PubMed Search"
               "PMID file"       "PMIDs from File"
               "PMID vector"     "PMIDs from API"
               "fact"            "PMIDs from FACTS"
               "EndNote file"    "EndNote XML"
               "legacy"          "Legacy Import"
               "PDF Zip file"    "PDF Zip File"
               stype))))

(reg-sub
 :source/display-info
 (fn [[_ source-id project-id]]
   [(subscribe [:project/sources source-id project-id])])
 (fn [[source] _]
   (let [stype (-> source :meta :source)]
     (cond (= "PubMed search" stype)
           (-> source :meta :search-term str)

           (in? ["PMID file" "EndNote file" "PDF Zip file"] stype)
           (-> source :meta :filename str)))))

(defn SourceArticlesLink [source-id]
  [:div.ui.primary.tiny.left.labeled.icon.button.view-articles
   {:on-click (util/wrap-user-event
               #(dispatch [:articles/load-source-filters [source-id]]))}
   [:i.list.icon]
   "View Articles"])

(defn SourceInfoView [project-id source-id]
  (let [{:keys [meta enabled]} @(subscribe [:project/sources source-id])
        {:keys [s3-file]} meta
        source-type @(subscribe [:source/display-type source-id])
        import-label @(subscribe [:source/display-info source-id])]
    [:div.ui.middle.aligned.stackable.grid.segment.source-info
     [:div.row
      [:div.six.wide.middle.aligned.left.aligned.column
       {:style {:padding-right "0.25rem"}}
       [:div.ui.large.label.source-type (str source-type)]
       (when enabled
         [SourceArticlesLink source-id])]
      [:div.ten.wide.right.aligned.middle.aligned.column
       (when import-label
         [:div.import-label.ui.large.basic.label
          [:span.import-label
           (if (and s3-file (:filename s3-file) (:key s3-file))
             [:a {:href (str "/api/sources/download/" project-id "/" source-id)
                  :target "_blank"
                  :download (:filename s3-file)}
              import-label " " [:i.download.icon]]
             import-label)]])]]]))

(defn source-name
  "Given a source-id, return the source name vector"
  [source-id]
  (->> @(subscribe [:project/sources])
       (filter #(= source-id (:source-id %)))
       first :meta meta->source-name-vector))

(defonce polling-sources? (r/atom false))

(defn poll-project-sources [project-id source-id]
  (when (not @polling-sources?)
    (reset! polling-sources? true)
    (dispatch [:fetch [:project/sources project-id]])
    (let [sources (subscribe [:project/sources])
          article-counts (subscribe [:project/article-counts])
          browser-test? (some-> @(subscribe [:user/display]) (str/includes? "browser+test"))
          first-source? (empty? (->> @sources (remove #(-> % :meta :importing-articles?))))
          source-updating?
          (fn [source-id]
            (or (loading/action-running? [:sources/delete project-id source-id])
                (let [[source] (filter #(= (:source-id %) source-id) @sources)]
                  (let [{:keys [importing-articles? deleting?]} (:meta source)]
                    (or (and (true? importing-articles?)
                             (not (source-import-timed-out? source)))
                        (true? deleting?))))))]
      (util/continuous-update-until
       (fn [] (dispatch [:fetch [:project/sources project-id]]))
       (fn [] (not (source-updating? source-id)))
       (fn []
         (reset! polling-sources? false)
         (dispatch [:reload [:project project-id]])
         (when (and first-source? (not browser-test?))
           (dispatch [:data/after-load [:project project-id] :poll-source-redirect
                      #(when (some-> @article-counts :total pos?)
                         (nav/nav-scroll-top (project-uri project-id "/articles")))])))
       600))))

(defn ArticleSource [source]
  (let [project-id @(subscribe [:active-project-id])
        {:keys [meta source-id date-created
                article-count labeled-article-count
                enabled]} source
        {:keys [importing-articles? deleting?]} meta
        polling? @polling-sources?
        delete-running? (loading/action-running?
                         [:sources/delete project-id source-id])
        timed-out? (source-import-timed-out? source)
        segment-class (if enabled nil "secondary")]
    (when (or (and (true? importing-articles?) (not timed-out?))
              deleting? delete-running?)
      (poll-project-sources project-id source-id)
      nil)
    [:div.project-source>div.ui.segments.project-source
     [SourceInfoView project-id source-id]
     [:div.ui.segment.source-details
      {:class segment-class}
      [:div.ui.middle.aligned.stackable.grid>div.row
       (cond
         (= (:source meta) "legacy")
         (list
          [:div.sixteen.wide.column.left.aligned.reviewed-count
           {:key :reviewed-count}
           [:div
            (.toLocaleString labeled-article-count)
            " of "
            (.toLocaleString article-count)
            " reviewed"]])

         ;; when source is currently being deleted
         (or deleting? delete-running?)
         (list
          [:div.eight.wide.column.left.aligned
           {:key :deleting}
           [:div "Deleting source..."]]
          [:div.six.wide.column.placeholder
           {:key :placeholder}]
          [:div.two.wide.column.right.aligned
           {:key :loader}
           [:div.ui.small.active.loader]])

         ;; when import has failed or timed out
         (or (= importing-articles? "error") timed-out?)
         (list
          [:div.eight.wide.column.left.aligned
           {:key :import-failed}
           "Import error"]
          ;; need to check if the user is an admin
          ;; before displaying this option
          [:div.eight.wide.column.right.aligned
           {:key :buttons}
           [DeleteArticleSource source-id]])

         ;; when articles are still loading
         (and (true? importing-articles?) polling? article-count (> article-count 0))
         (list
          [:div.eight.wide.column.left.aligned.loaded-count
           {:key :loaded-count}
           [:div
            (str (.toLocaleString article-count) " "
                 (article-or-articles article-count) " loaded")]]
          [:div.six.wide.column.placeholder
           {:key :placeholder}]
          [:div.two.wide.column.right.aligned
           {:key :loader}
           [:div.ui.small.active.loader]])

         ;; when articles have been imported
         (and (false? importing-articles?) labeled-article-count article-count)
         (list
          [:div.source-description.column.left.aligned
           {:key :reviewed-count
            :class (if (admin?)
                     (if (util/desktop-size?)
                       "fourteen wide" "thirteen wide")
                     "sixteen wide")}
           [:div.ui.two.column.stackable.left.aligned.middle.aligned.grid
            ;; total/reviewed count
            [:div.column
             [:span.reviewed-count (.toLocaleString labeled-article-count)]
             " of "
             [:span.total-count (.toLocaleString article-count)]
             " " (article-or-articles article-count) " reviewed"]
            ;; unique count
            (when-let [unique-articles-count (:unique-articles-count source)]
              [:div.column
               [:span.unique-count (.toLocaleString unique-articles-count)]
               " unique " (article-or-articles unique-articles-count)])
            (doall (for [{shared-count :count, overlap-source-id :overlap-source-id}
                         (filter #(pos? (:count %)) (:overlap source))]
                     (let [src-type @(subscribe [:source/display-type overlap-source-id])
                           src-info (some-> @(subscribe [:source/display-info overlap-source-id])
                                            (sutil/string-ellipsis 40))]
                       ^{:key [:shared source-id overlap-source-id]}
                       [:div.column (.toLocaleString shared-count) " shared: "
                        [:div.ui.label.source-shared src-type [:div.detail src-info]]])))]]
          (when (admin?)
            [:div.column.right.aligned.source-actions
             {:key :buttons
              :class (if (util/desktop-size?)
                       "two wide" "three wide")}
             [ToggleArticleSource source-id enabled]
             (when (and (<= labeled-article-count 0))
               [DeleteArticleSource source-id])]))
         :else
         (list
          [:div.eight.wide.column.left.aligned
           {:key :import-status}
           "Starting import..."]
          [:div.six.wide.column.placeholder
           {:key :placeholder}]
          [:div.two.wide.column.right.aligned
           {:key :loader}
           [:div.ui.small.active.loader]]))]]]))

(defn ProjectSourcesList []
  (ensure-state)
  (let [sources @(subscribe [:project/sources])
        article-count (:total @(subscribe [:project/article-counts]))]
    [:div#project-sources
     (if (empty? sources)
       [:h4.ui.block.header
        (if (and article-count (> article-count 0))
          "No article sources added yet"
          "No articles imported yet")]
       [:div
        [:h4.ui.large.block.header
         "Article Sources"]
        [:div.project-sources-list
         (doall (map (fn [source]
                       ^{:key (:source-id source)}
                       [ArticleSource source])
                     (sort-by (fn [{:keys [source-id enabled]}]
                                [(not enabled) (- source-id)])
                              sources)))]])]))

(defn ImportArticlesView []
  (ensure-state)
  (let [import-tab (r/cursor state [:import-tab])
        active-tab (or @import-tab :pubmed)
        full-size? (util/full-size?)]
    [:div#import-articles
     {:style {:margin-bottom "1em"}}
     [:h4.ui.large.block.header
      "Import Articles"]
     [:div.ui.segments
      [:div.ui.attached.segment.import-menu
       [ui/tabbed-panel-menu
        [{:tab-id :pubmed
          :content (if full-size? "PubMed Search" "PubMed")
          :action #(reset! import-tab :pubmed)}
         {:tab-id :ris-file
          :content "RIS File"
          :action #(reset! import-tab :ris-file)}
         {:tab-id :endnote
          :content (if full-size? "EndNote XML" "EndNote")
          :action #(reset! import-tab :endnote)}
         {:tab-id :pmid
          :content "PMIDs"
          :action #(reset! import-tab :pmid)}
         {:tab-id :zip-file
          :content "PDF Files"
          :action #(reset! import-tab :zip-file)}]
        active-tab
        "import-source-tabs"]]
      [:div.ui.attached.secondary.segment
       (case active-tab
         :pubmed   [pubmed/SearchBar]
         :pmid     [ImportPMIDsView]
         :endnote  [ImportEndNoteView]
         :zip-file [ImportPDFZipsView]
         :ris-file [ImportRISView])]
      (when (= active-tab :pubmed)
        [pubmed/SearchActions (any-source-processing?)])]
     (when (= active-tab :pubmed)
       [pubmed/SearchResultsContainer])]))

(defn ProjectSourcesPanel []
  (ensure-state)
  (let [project-id @(subscribe [:active-project-id])
        read-only-message-closed? (r/cursor state [:read-only-message-closed?])
        project? @(subscribe [:have? [:project project-id]])
        lapsed? @(subscribe [:project/subscription-lapsed?])]
    (with-loader [(when (and project? (not lapsed?))
                    [:project/sources project-id])] {}
      [:div
       (when (admin?)
         [ImportArticlesView])
       [ReadOnlyMessage
        "Managing sources is restricted to project administrators."
        read-only-message-closed?]
       [ProjectSourcesList]])))

(defmethod panel-content panel []
  (fn [child]
    [:div#add-articles.project-content
     [ProjectSourcesPanel]]))
