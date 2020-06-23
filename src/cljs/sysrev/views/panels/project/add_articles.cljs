(ns sysrev.views.panels.project.add-articles
  (:require [clojure.string :as str]
            [cljs-time.core :as t]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe reg-sub reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.nav :as nav]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.ctgov :as ctgov]
            [sysrev.views.panels.pubmed :as pubmed]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.panels.project.source-view :refer [EditJSONView]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.semantic :refer [Popup Icon ListUI ListItem Button]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :add-articles] {:state-var state})

(def initial-state {:read-only-message-closed? false
                    :editing-view? false})

(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(reg-event-fx
 :add-articles/reset-state!
 [trim-v]
 (fn [_]
   (reset! state initial-state)
   {}))

(reg-event-fx
 :add-articles/reset-import-tab!
 [trim-v]
 (fn [_ [new-val]]
   (reset! (r/cursor state [:import-tab]) new-val)
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
  (util/pluralize item-count "article"))

(defn- source-import-timed-out? [source]
  (let [{:keys [meta date-created]} source
        {:keys [importing-articles?]} meta]
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
      "Upload a plain text file containing a list of PubMed IDs (one per line)."
      [Popup {:hoverable true
              :trigger (r/as-element [Icon {:name "question circle"}])
              :content (r/as-element
                        [:div
                         [:h5 [:p "Export a PMID file from PubMed"]]
                         [ListUI
                          [ListItem "1. Click " [:b "Send To"] " the top right of the PubMed search results"]
                          [ListItem "2. Select 'File' for " [:b "Choose Destination"]]
                          [ListItem "3. Select 'PMID file' for " [:b "Format"]]
                          [ListItem "4. Click " [:b "Create File"] " button to download"]
                          [ListItem "4. Import the downloaded txt file with the " [:b "Upload Text File..."] " import button below"]]])}]
      [:a {:href "https://www.youtube.com/watch?v=8XTxAbaTpIY"
           :target "_blank"} [Icon {:name "video camera"}]]]
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
        (any-source-processing?) (str " disabled"))
      {} :post-error-text "Try editing your file to fit the upload instructions above or contact us at info@insilica.co with a copy of your zip file."]]))

(defn ImportRISView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.segment.import-upload
     [:h5 "RIS / RefMan"
      [Popup {:hoverable true
              :trigger (r/as-element [Icon {:name "question circle"}])
              :content (r/as-element
                        [:div
                         [:h5 "Supported Exports"]
                         [ListUI
                          [ListItem "Scopus"]
                          [ListItem "IEEE Explore"]
                          [ListItem "EndNote Online"]
                          [ListItem "Zotero"]
                          [ListItem "...and many more"]]
                         [:b "Note: Make sure to include abstracts when exporting files!"]])}]
      [:a {:href "https://www.youtube.com/watch?v=N_Al2NfIUCw"
           :target "_blank"} [Icon {:name "video camera"}]]]
     [:p "Having difficulties importing your RIS file? We recommend using the free, cross-platform tool " [:a {:href "https://zotero.org" :target "_blank"} "zotero"] " to convert your RIS file to a sysrev compatible version. "
      "We've made a "
      [:a {:href "https://www.youtube.com/watch?v=N_Al2NfIUCw" :target "_blank"} "quick video tutorial"]
      " describing the process. Please make sure your RIS file is under 7mb. You can upload multiple files."]
     [ui/UploadButton
      (str "/api/import-articles/ris/" project-id)
      #(dispatch [:reload [:project/sources project-id]])
      "Upload RIS file..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))
      {}
      :post-error-text "Try editing your file to fit the upload instructions above or contact us at info@insilica.co with a copy of your RIS file."]]))

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
(defn meta->source-name-vector [{:keys [source] :as meta}]
  (condp = source
    "PubMed search"  ["PubMed Search" (str (:search-term meta))]
    "PMID file"      ["PMIDs from File" (:filename meta)]
    "PMID vector"    ["PMIDs from API" nil]
    "fact"           ["PMIDs from FACTS" nil]
    "EndNote file"   ["EndNote XML" (:filename meta)]
    "legacy"         ["Legacy Import" nil]
    "PDF Zip file"   ["PDF Zip File" (:filename meta)]
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

(reg-sub :source/display-info
         (fn [[_ source-id project-id]]
           (subscribe [:project/sources source-id project-id]))
         (fn [{:keys [meta] :as source} _]
           (case (:source meta)
             "PubMed search"      (str (:search-term meta))
             ("PMID file" "EndNote file" "PDF Zip file"
              "RIS file")         (str (:filename meta))
             "Datasource Query"   (str (:query meta))
             "Dataset"            (str "Dataset ID: " (:dataset-id meta))
             "Datasource"         (str "Datasource ID: " (:datasource-id meta))
             "Project Filter"     (let [{:keys [source-project-id filters]} (:meta source)]
                                    (-> {:source-project-id source-project-id :filters filters}
                                        (util/write-json true)))
             nil)))

(defn SourceArticlesLink [source-id]
  [:div.ui.primary.tiny.left.labeled.icon.button.view-articles
   {:on-click (util/wrap-user-event
               #(dispatch [:articles/load-source-filters [source-id]]))}
   [:i.list.icon]
   "View Articles"])

(defn SourceInfoView [project-id source-id]
  (let [{:keys [meta enabled]} @(subscribe [:project/sources source-id])
        {:keys [s3-file source filename]} meta
        source-type @(subscribe [:source/display-type source-id])
        import-label @(subscribe [:source/display-info source-id])]
    [:div.ui.middle.aligned.stackable.grid.segment.source-info {:data-source-id source-id}
     [:div.row
      [:div.six.wide.middle.aligned.left.aligned.column
       {:style {:padding-right "0.25rem"}}
       [:div.ui.large.label.source-type {:data-name source-type}
        (str source-type)]
       (when enabled
         [SourceArticlesLink source-id])]
      [:div.ten.wide.right.aligned.middle.aligned.column
       (when import-label
         [:div.import-label.ui.large.basic.label
          [:span.import-label
           (cond (and s3-file (:filename s3-file) (:key s3-file))
                 [:a {:href (str "/api/sources/download/" project-id "/" source-id)
                      :target "_blank"
                      :download filename}
                  import-label " " [:i.download.icon]]
                 (= source "RIS file")
                 [:a {:href (str "/api/sources/download/" project-id "/" source-id)
                      :target "_blank"
                      :download (:filename s3-file)}
                  import-label " " [:i.download.icon]]
                 (= source "Project Filter")
                 [:pre {:style {:font-size "0.95em" :margin 0}} import-label]
                 :else
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
                (let [[source] (filter #(= (:source-id %) source-id) @sources)
                      {:keys [importing-articles? deleting?]} (:meta source)]
                  (or (and (true? importing-articles?)
                           (not (source-import-timed-out? source)))
                      (true? deleting?)))))]
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
       600))
    nil))

(defn ArticleSource [_source]
  (r/create-class
   {:reagent-render
    (fn [source]
      (let [project-id @(subscribe [:active-project-id])
            {:keys [meta source-id article-count labeled-article-count enabled]} source
            {:keys [importing-articles? deleting?]} meta
            source-name (:source meta)
            delete-running? (loading/action-running? [:sources/delete project-id source-id])
            segment-class (if enabled nil "secondary")
            editing-view? (r/cursor state [source-id :editing-view?])
            timed-out? (source-import-timed-out? source)
            polling? @polling-sources?
            sample-article (subscribe [:project/sample-article project-id source-id])]
        (when (or (and (true? importing-articles?) (not timed-out?))
                  deleting? delete-running?)
          (poll-project-sources project-id source-id))
        (when-not (seq @sample-article)
          (dispatch [:fetch [:project/get-source-sample-article project-id source-id]]))
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
             (if @editing-view?
               [EditJSONView {:source (subscribe [:project/sources source-id])
                              :editing-view? editing-view?}]
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
                                                  (util/string-ellipsis 40))]
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
                     [DeleteArticleSource source-id])
                   ;; should include any JSON / XML sources
                   ;; TODO: Fix this so CT.gov uses regular article content
                   ;; this should only dispatch on mimetype, not on source-name
                   (when (or (= source-name "CT.gov search")
                             (= (:mimetype @sample-article) "application/json"))
                     [Button {:fluid true
                              :size "tiny"
                              :style {:margin-top "0.5em"
                                      :margin-right "0"}
                              :onClick #(swap! editing-view? not)}
                      "Edit View"])])))
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
    :component-did-mount (fn [_]
                           (reset! state initial-state))}))

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

(defn EnableCTNotice
  []
  [:div.ui.segment.import-upload
   [:div
    [:a {:href "https://www.youtube.com/watch?v=Qf-KWG7laLY" :target "_blank"}
     "Click here"]
    " to see a demo of our "
    [:a {:href "https://clinicaltrials.gov" :target "_blank"} "ClinicalTrials.gov"]
    " integration. To unlock direct access, please"
    [:a {:href
         (str "mailto:info@sysrev.com?"
              "subject=How can I use ClinicalTrials.gov with sysrev?"
              "&body=Hi, I would like to know more about using"
              " ClinicalTrials.gov in sysrev to conduct reviews."
              " Please let me know how I can enable this feature. Thanks!")
         :target "_blank"} " contact us"]"."]])

(defn ImportArticlesView []
  (ensure-state)
  (let [import-tab (r/cursor state [:import-tab])
        active-tab (or @import-tab :zip-file)
        full-size? (util/full-size?)]
    [:div#import-articles
     {:style {:margin-bottom "1em"}}
     [:h4.ui.large.block.header
      "Import Articles"
      [:span {:style {:font-size "0.9em"}}
       [:a {:href "https://www.youtube.com/watch?v=dHISlGOm7A8&t=15"
            :target "_blank"
            :style {:margin-left "0.25em"}}
        [Icon {:name "video camera"}]]]]
     [:div.ui.segments
      [:div.ui.attached.segment.import-menu
       [ui/tabbed-panel-menu [{:tab-id :zip-file
                               :content "PDF Files"
                               :action #(reset! import-tab :zip-file)}
                              {:tab-id :ris-file
                               :content "RIS / RefMan"
                               :action #(reset! import-tab :ris-file)}
                              {:tab-id :pubmed
                               :content (if full-size? "PubMed Search" "PubMed")
                               :action #(reset! import-tab :pubmed)}
                              {:tab-id :ctgov
                               :content [:div "ClinicalTrials.gov" [:sup {:style {:color "red"}} " beta"]]
                               :action #(reset! import-tab :ctgov)}
                              {:tab-id :pmid
                               :content "PMIDs"
                               :action #(reset! import-tab :pmid)}
                              {:tab-id :endnote
                               :content (if full-size? "EndNote XML" "EndNote")
                               :action #(reset! import-tab :endnote)}]
        active-tab "import-source-tabs"]]
      [:div.ui.attached.secondary.segment
       (case active-tab
         :pubmed    [pubmed/SearchBar]
         :pmid      [ImportPMIDsView]
         :endnote   [ImportEndNoteView]
         :zip-file  [ImportPDFZipsView]
         :ris-file  [ImportRISView]
         :ctgov (if (and (= js/window.location.hostname "sysrev.com")
                         (not (some #{@(subscribe [:user/email])}
                                    #{"amarluniwal@gmail.com"
                                      "geoffreyweiner@gmail.com"
                                      "james@insilica.co"
                                      "tom@insilica.co"
                                      "jeff@insilica.co"
                                      "tj@insilica.co"
                                      "g.callegaro@lacdr.leidenuniv.nl"})))
                  [EnableCTNotice]
                  [ctgov/SearchBar]))]
      (when (= active-tab :pubmed)
        [pubmed/SearchActions (any-source-processing?)])
      (when (= active-tab :ctgov)
        [ctgov/SearchActions (any-source-processing?)])]
     (when (= active-tab :pubmed)
       [pubmed/SearchResultsContainer])
     (when (= active-tab :ctgov)
       [ctgov/SearchResultsContainer])]))

(defn ProjectSourcesPanel []
  (ensure-state)
  (let [project-id @(subscribe [:active-project-id])
        read-only-message-closed? (r/cursor state [:read-only-message-closed?])
        project? @(subscribe [:have? [:project project-id]])
        lapsed? @(subscribe [:project/subscription-lapsed?])]
    (with-loader [(when (and project? (not lapsed?))
                    [:project/sources project-id])] {}
      [:div
       (when (admin?) [ImportArticlesView])
       [ReadOnlyMessage "Managing sources is restricted to project administrators."
        read-only-message-closed?]
       [ProjectSourcesList]])))

(defmethod panel-content panel []
  (fn [_child] [:div#add-articles.project-content
                [ProjectSourcesPanel]]))
