(ns sysrev.views.panels.project.add-articles
  (:require [cljs-time.core :as t]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch dispatch-sync subscribe reg-sub reg-event-db trim-v]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :as data]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.nav :as nav]
            [sysrev.views.ctgov :as ctgov]
            [sysrev.views.pubmed :as pubmed]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.panels.project.source-view :refer [EditJSONView]]
            [sysrev.views.uppy :refer [Dashboard]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.semantic :refer [Popup Icon ListUI ListItem Button]]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel
                                          sr-defroute-project]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :add-articles]
                   :state state :get [panel-get] :set [panel-set])

;; this sets the default tab
(reg-sub :add-articles/import-tab #(or (panel-get % :import-tab) nil))

(reg-event-db :add-articles/import-tab [trim-v]
              (fn [db [tab-id]]
                (panel-set db :import-tab tab-id)))

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
  (or (action/running? #{:sources/delete :sources/toggle-source})
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

(defn ImportPDFsView []
  (let [project-id @(subscribe [:active-project-id])
        csrf-token @(subscribe [:csrf-token])]
    [:div {:style {:margin-left "auto"
                   :margin-right "auto"
                   :margin-top "1em"
                   :max-width "600px"}}
     [Dashboard {:endpoint (str "/api/import-articles/pdfs/" project-id)
                 :csrf-token csrf-token
                 :on-complete #(dispatch [:reload [:project/sources project-id]])
                 :project-id project-id}]]))

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
     [:p "Having difficulties importing your RIS file? We recommend using the free, cross-platform tool " [:a {:href "https://zotero.org" :target "_blank"} "Zotero"] " to convert your RIS file to a sysrev compatible version. "
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
      :post-error-text "Try processing your file with Zotero using the 'Having difficulties importing your RIS file?' instructions above."]]))

(defn DeleteArticleSource [source-id]
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.tiny.fluid.labeled.icon.button.delete-button
     {:class (when (any-source-processing?) "disabled")
      :on-click #(run-action :sources/delete project-id source-id)}
     "Delete"
     [:i.red.times.circle.icon]]))

(defn ToggleArticleSource [source-id enabled?]
  (let [project-id @(subscribe [:active-project-id])
        action [:sources/toggle-source project-id source-id (not enabled?)]
        loading? (action/running? action)]
    [:button.ui.tiny.fluid.labeled.icon.button
     {:on-click #(dispatch [:action action])
      :class (cond loading?                 "loading"
                   (any-source-processing?) "disabled")}
     (when-not loading?
       [:i.circle.icon {:class (css [enabled? "green" :else "grey"])}])
     (if enabled? "Enabled" "Disabled")]))

;; TODO: these (:source meta) values should be stored as identifiers
;;       from an enforced set of possible values and shouldn't be
;;       mistakable for a description intended for users
(defn ^:unused meta->source-name-vector [{:keys [source] :as meta}]
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

(defn ^:unused source-name
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
          first-source? (empty? (->> @sources (remove #(-> % :meta :importing-articles?))))
          source-updating?
          (fn [source-id]
            (or (action/running? [:sources/delete project-id source-id])
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
         (when (and first-source? #_(not browser-test?))
           (dispatch [:data/after-load [:project project-id] :poll-source-redirect])))
       600))
    nil))

(defn ArticleSource [_source]
  (let [editing-view? (r/atom false)]
    (fn [source]
      (let [project-id @(subscribe [:active-project-id])
            {:keys [meta source-id article-count labeled-article-count enabled]} source
            {:keys [importing-articles? deleting?]} meta
            source-name (:source meta)
            delete-running? (action/running? [:sources/delete project-id source-id])
            segment-class (if enabled nil "secondary")
            timed-out? (source-import-timed-out? source)
            polling? @polling-sources?
            sample-article @(subscribe [:project-source/sample-article project-id source-id])
            admin? @(subscribe [:member/admin? true])]
        (when (and (nil? sample-article) admin?)
          (dispatch [:require [:project-source/sample-article project-id source-id]]))
        (when (or (and (true? importing-articles?) (not timed-out?))
                  deleting? delete-running?)
          (poll-project-sources project-id source-id))
        [:div.project-source>div.ui.segments.project-source
         [SourceInfoView project-id source-id]
         [:div.ui.segment.source-details {:class segment-class}
          [:div.ui.middle.aligned.stackable.grid>div.row
           (cond
             (= (:source meta) "legacy")
             (list [:div.sixteen.wide.column.left.aligned.reviewed-count {:key :reviewed-count}
                    [:div (.toLocaleString labeled-article-count)
                     " of " (.toLocaleString article-count) " reviewed"]])
             ;; when source is currently being deleted
             (or deleting? delete-running?)
             (list [:div.eight.wide.column.left.aligned  {:key :deleting}
                    [:div "Deleting source..."]]
                   [:div.six.wide.column.placeholder     {:key :placeholder}]
                   [:div.two.wide.column.right.aligned   {:key :loader}
                    [:div.ui.small.active.loader]])
             ;; when import has failed or timed out
             (or (= importing-articles? "error") timed-out?)
             (list [:div.eight.wide.column.left.aligned  {:key :import-failed}
                    "Import error"]
                   ;; need to check if the user is an admin
                   ;; before displaying this option
                   [:div.eight.wide.column.right.aligned {:key :buttons}
                    [DeleteArticleSource source-id]])
             ;; when articles are still loading
             (and (true? importing-articles?) polling? (pos-int? article-count))
             (list [:div.eight.wide.column.left.aligned.loaded-count {:key :loaded-count}
                    [:div (str (.toLocaleString article-count) " "
                               (article-or-articles article-count) " loaded")]]
                   [:div.six.wide.column.placeholder   {:key :placeholder}]
                   [:div.two.wide.column.right.aligned {:key :loader}
                    [:div.ui.small.active.loader]])
             ;; when articles have been imported
             (and (false? importing-articles?) labeled-article-count article-count)
             (if @editing-view?
               [EditJSONView {:source (subscribe [:project/sources source-id])
                              :editing-view? editing-view?}]
               (list
                [:div.source-description.column.left.aligned
                 {:key :reviewed-count
                  :class (css [(not admin?)         "sixteen"
                               (util/desktop-size?) "fourteen"
                               :else                "thirteen"] "wide")}
                 [:div.ui.two.column.stackable.left.aligned.middle.aligned.grid
                  ;; total/reviewed count
                  [:div.column
                   [:span.reviewed-count (.toLocaleString labeled-article-count)]
                   " of " [:span.total-count (.toLocaleString article-count)]
                   " " (article-or-articles article-count) " reviewed"]
                  ;; unique count
                  (when-let [unique-articles-count (:unique-articles-count source)]
                    [:div.column
                     [:span.unique-count {:data-count (str unique-articles-count)}
                      (.toLocaleString unique-articles-count)]
                     " unique " (article-or-articles unique-articles-count)])
                  (doall
                   (for [{shared-count :count, overlap-source-id :overlap-source-id}
                         (filter #(pos? (:count %)) (:overlap source))]
                     (let [src-type @(subscribe [:source/display-type overlap-source-id])
                           src-info (some-> @(subscribe [:source/display-info overlap-source-id])
                                            (util/ellipsis-middle 40))]
                       ^{:key [:shared source-id overlap-source-id]}
                       [:div.column (.toLocaleString shared-count) " shared: "
                        [:div.ui.label.source-shared src-type [:div.detail src-info]]])))]]
                (when admin?
                  [:div.column.right.aligned.source-actions
                   {:key :buttons
                    :class (if (util/desktop-size?) "two wide" "three wide")}
                   [ToggleArticleSource source-id enabled]
                   (when (and (<= labeled-article-count 0))
                     [DeleteArticleSource source-id])
                   ;; should include any JSON / XML sources
                   ;; TODO: Fix this so CT.gov uses regular article content
                   ;; this should only dispatch on mimetype, not on source-name
                   (when (or (= source-name "CT.gov search")
                             (= (:mimetype sample-article) "application/json"))
                     [Button {:size "tiny" :fluid true :style {:margin-top "0.5em"
                                                               :margin-right "0"}
                              :on-click #(swap! editing-view? not)}
                      "Edit View"])])))
             :else
             (list [:div.eight.wide.column.left.aligned {:key :import-status}
                    "Starting import..."]
                   [:div.six.wide.column.placeholder    {:key :placeholder}]
                   [:div.two.wide.column.right.aligned  {:key :loader}
                    [:div.ui.small.active.loader]]))]]]))))

(defn ProjectSourcesList []
  (let [sources @(subscribe [:project/sources])]
    (when-not (empty? sources)
      [:div#project-sources
       [:div
        [:h3 {:style {:margin-top "0"}} "Article Sources"]
        [:div.project-sources-list
         (doall (for [source (sort-by (fn [{:keys [source-id enabled]}]
                                        [(not enabled) (- source-id)])
                                      sources)]
                  ^{:key (:source-id source)}
                  [ArticleSource source]))]]])))

(defn EnableCTNotice []
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

(defn CustomDatasource []
  [:div.ui.segment {:style {:margin-left "auto"
                            :margin-right "auto"
                            :max-width "600px"}}
   [:b "Need to review something else? " [:a {:href "/managed-review"} "Talk to us"] " about integrating unique datasources including JSON, XML, and more."]])

(defn DatasourceIcon
  [{:keys [text value name]}]
  (let [active? (= @(subscribe [:add-articles/import-tab]) value)]
    [:div {:on-click #(dispatch-sync [:add-articles/import-tab value])
           :class (cond-> "datasource-item"
                    active? (str " active"))
           :style {:display "inline-block"
                   :text-align "center"
                   :margin "1em 1em 0 1em"}}
     [:div {:style {:flex "0 0 120px"
                    :cursor "pointer"}}
      [Icon {:name name
             :size "big"}]
      [:p {:style {:margin-top "1em"} } text]]]))

(defn DatasourceIconList [options]
  [:div
   (for [option options]
     ^{:key (:value option)}
     [DatasourceIcon option])])

(defn ImportArticlesView []
  (let [active-tab (subscribe [:add-articles/import-tab])
        sources @(subscribe [:project/sources])]
    [:div#import-articles {:style {:margin-bottom "1em"}}
     (when (empty? sources)
       [:h4.ui.header
        [:p "Your project " [:span {:style {:color "red"}} "requires articles"] " before you can begin working on it."]])
     [:div
      [DatasourceIconList [{:value :pdfs
                            :text "PDF files"
                            :name "file pdf outline"}
                           {:value :pdf-zip
                            :text "PDF.zip"
                            :name "file archive outline"}
                           {:text "PubMed"
                            :value :pubmed
                            :name "search"}
                           {:value :pmid
                            :text "PMID file"
                            :name "file outline"}
                           {:value :ris-file
                            :text "RIS / RefMan"
                            :name "file alternate outline"}
                           {:value :endnote
                            :text "EndNote XML"
                            :name "file code outline"}
                           {:value :ctgov
                            :text "ClinicalTrials (beta)"
                            :name "hospital outline"}
                           {:value :custom
                            :text "Custom Datasource"
                            :name "database"}]]
      (when @active-tab
        (condp =  @active-tab
          :pubmed    [pubmed/SearchBar]
          :pmid      [ImportPMIDsView]
          :endnote   [ImportEndNoteView]
          :pdfs      [ImportPDFsView]
          :pdf-zip   [ImportPDFZipsView]
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
                   [ctgov/SearchBar])
          :custom [CustomDatasource]
          nil))
      (condp =  @active-tab
        :pubmed [pubmed/SearchActions (any-source-processing?)]
        :ctgov  [ctgov/SearchActions (any-source-processing?)]
        nil)]
     (condp =  @active-tab
       :pubmed [pubmed/SearchResultsContainer]
       :ctgov  [ctgov/SearchResultsContainer]
       nil)]))

(defn DocumentImport []
  (let [view-import-button? (r/atom true)
        sources @(subscribe [:project/sources])]
    (fn []
      (if (and (empty? sources) @view-import-button?)
        [Button {:id "enable-import"
                 :size "small"
                 :positive true
                 :on-click #(do (reset! view-import-button? false)
                                (dispatch-sync [:add-articles/import-tab nil]))}
         "Import Documents"]
        [ImportArticlesView]))))

(defn ProjectSourcesPanel []
  (let [project-id @(subscribe [:active-project-id])
        project? @(subscribe [:have? [:project project-id]])
        lapsed? @(subscribe [:project/subscription-lapsed?])
        admin? @(subscribe [:member/admin? true])]
    (with-loader [(when (and project? (not lapsed?))
                    [:project/sources project-id])] {}
      [:div
       (when admin? [DocumentImport])
       [ReadOnlyMessage "Managing sources is restricted to project administrators."
        (r/cursor state [:read-only-message-closed?])]
       (when-not (empty? @(subscribe [:project/sources]))
         [ProjectSourcesList])])))

(def-panel :project? true :panel panel
  :uri "/add-articles" :params [project-id] :name add-articles
  :on-route (do (data/reload :project/sources project-id)
                (dispatch [:set-active-panel panel]))
  :content [:div#add-articles.project-content
            [ProjectSourcesPanel]])

;; redirect to "/add-articles" when "Manage" tab is clicked
(sr-defroute-project manage-project "/manage" [project-id]
                     (nav/nav (project-uri project-id "/add-articles")
                              :redirect true))
