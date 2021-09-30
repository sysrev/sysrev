(ns sysrev.views.panels.project.add-articles
  (:require [cljs-time.core :as t]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch dispatch-sync subscribe reg-sub
                                   reg-event-db reg-event-fx trim-v]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :as data]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.nav :as nav]
            [sysrev.views.ctgov :as ctgov]
            [sysrev.views.fda-drugs-docs :as fda-drugs-docs]
            [sysrev.views.pubmed :as pubmed]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.panels.project.source-view :as source-view]
            [sysrev.views.uppy :as uppy]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.views.components.core :as ui]
            [sysrev.views.semantic :refer [Popup Icon ListUI ListItem Button
                                           Modal ModalHeader ModalContent ModalDescription
                                           Form Checkbox FormField TextArea]]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel
                                          sr-defroute-project]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :add-articles]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

;; this sets the default tab
(reg-sub :add-articles/import-tab #(or (panel-get % :import-tab) nil))

(reg-event-db :add-articles/import-tab [trim-v]
              (fn [db [tab-id]]
                (panel-set db :import-tab tab-id)))

(reg-event-db ::add-documents-visible [trim-v]
              (fn [db [value]] (panel-set db :add-documents-visible value)))

(reg-sub ::add-documents-visible
         (fn [[_ project-id]]
           [(subscribe [::get [:add-documents-visible]])
            (subscribe [:have? [:project/sources project-id]])
            (subscribe [:project/sources])])
         (fn [[visible have-sources? sources]]
           (cond (some? visible)                       visible
                 (and have-sources? (empty? sources))  true
                 :else                                 false)))

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

(def-action :sources/update
  :uri (fn [] "/api/update-source")
  :content (fn [project-id source-id {:keys [check-new-results? import-new-results? notes]}]
             {:project-id project-id
              :source-id source-id
              :check-new-results? check-new-results?
              :import-new-results? import-new-results?
              :notes notes})
  :process
  (fn [_ [project-id source-id _] {:keys [success message] :as _result}]
    (when success
      {:dispatch-n
       (list [:reload [:review/task project-id]]
             [:reload [:project project-id]]
             [:reload [:project/sources project-id]]
             [::set [:edit-source-modal source-id :open] false]
             [:alert {:content message :opts {:success true}}])})))

(def-action :sources/re-import
  :uri (fn [] "/api/re-import-source")
  :content (fn [project-id source-id]
             {:project-id project-id
              :source-id source-id})
  :process
  (fn [_ [project-id _] {:keys [success] :as _result}]
    (when success
      {:dispatch-n
       (list [:reload [:project/sources project-id]]
             [:on-add-source project-id])})))

(reg-event-fx :on-add-source [trim-v]
              (fn [_ [project-id]]
                {:dispatch-n [[::add-documents-visible false]
                              [:poll-project-sources project-id]]}))

(defn EditSourceModal [source]
  (let [modal-state-path [:edit-source-modal (:source-id source)]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id @(subscribe [:active-project-id])
        project-plan  @(subscribe [:project/plan project-id])
        has-pro? (or (re-matches #".*@insilica.co" @(subscribe [:self/email]))
                     (plans-info/pro? project-plan))
        source-check-new-results? (r/cursor state (concat modal-state-path [:form-data :check-new-results?]))
        source-import-new-results? (r/cursor state (concat modal-state-path [:form-data :import-new-results?]))
        source-notes (r/cursor state (concat modal-state-path [:form-data :notes]))]
    (fn [source]
      [Modal {:trigger
              (r/as-element
               [:div.ui.tiny.fluid.labeled.icon.button.edit-button
                {:on-click
                 #(dispatch [::set modal-state-path
                             {:open true
                              :form-data {:check-new-results? (:check-new-results source)
                                          :import-new-results? (:import-new-results source)
                                          :notes (:notes source)}}])}
                "Edit" [:i.pencil.icon]])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader
        "Edit data source"]
       [ModalContent
        [ModalDescription
         [Form {:on-submit (util/wrap-prevent-default
                            #(dispatch [:action [:sources/update project-id (:source-id source)
                                                 {:check-new-results? @source-check-new-results?
                                                  :import-new-results? @source-import-new-results?
                                                  :notes @source-notes}]]))}
          [FormField
           [Checkbox {:label "Check new results"
                      :id "check-new-results-checkbox"
                      :checked @source-check-new-results?
                      :on-change
                      (util/on-event-checkbox-value
                       (fn [v]
                         (if has-pro?
                           (reset! source-check-new-results? v)
                           (dispatch [:alert
                                      {:header "Pro plan required"
                                       :content "This feature is only available for pro users."
                                       :opts {:info true}}])
                           #_
                           (dispatch [:toast
                                      {:title "Pro plan required"
                                       :class "blue"
                                       :message "This feature is only available for pro users."
                                       :displayTime 0
                                       :actions [{:text "Maybe later"}
                                                 {:text "Purchase plan"
                                                  :class "black"
                                                  :click #(dispatch [:nav "/user/plans"])}]}]))
                         true))}]
           " "
           (when-not has-pro?
             [:i.chess.king.icon.yellow {:title "Pro feature"}])]
          #_
          [FormField
           [Checkbox
            {:label "Auto import new results"
             :id "import-new-results-checkbox"
             :disabled (not @source-check-new-results?)
             :checked @source-import-new-results?
             :on-change (util/on-event-checkbox-value #(reset! source-import-new-results? %))}]]
          [FormField
           [:label "Notes"]
           [TextArea {:label "Notes"
                      :id "source-notes-input"
                      :default-value (:notes source)
                      :on-change (util/on-event-value #(reset! source-notes %))}]]
          [Button {:primary true
                   :id "save-source-btn"}
           "Save"]]]]])))

(defn article-or-articles
  "Return either the singular or plural form of article"
  [item-count]
  (util/pluralize item-count "article"))

(defn- source-import-timed-out? [source]
  (let [{:keys [meta import-date]} source
        {:keys [importing-articles?]} meta]
    (and (true? importing-articles?)
         (t/within? {:start (t/epoch)
                     :end (t/minus (t/now) (t/minutes 30))}
                    import-date))))

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
    [:div.ui
     [:h5
      "To create from EndNote, go to File > Export,"
      " and under \"Save file as type\" select \"XML\"."]
     [ui/UploadButton
      (str "/api/import-articles/endnote-xml/" project-id)
      #(dispatch [:on-add-source project-id])
      "Upload XML File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))]]))

(defn ImportPMIDsView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:h5
      "Upload a plain text file containing a list of PubMed IDs (one per line)."
      [Popup
       {:hoverable true
        :trigger (r/as-element [Icon {:name "question circle"}])
        :content
        (r/as-element
         [:div
          [:h5 [:p "Export a PMID file from PubMed"]]
          [ListUI
           [ListItem "1. Click " [:b "Send To"] " the top right of the PubMed search results"]
           [ListItem "2. Select 'File' for " [:b "Choose Destination"]]
           [ListItem "3. Select 'PMID file' for " [:b "Format"]]
           [ListItem "4. Click " [:b "Create File"] " button to download"]
           [ListItem "4. Import the downloaded txt file with the "
            [:b "Upload Text File..."] " import button below"]]])}]
      [:a {:href "https://www.youtube.com/watch?v=8XTxAbaTpIY"
           :target "_blank"} [Icon {:name "video camera"}]]]
     [ui/UploadButton
      (str "/api/import-articles/pmid-file/" project-id)
      #(dispatch [:on-add-source project-id])
      "Upload Text File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))]]))

(defn ImportPDFsView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div {:style {:margin-left "auto" :margin-right "auto"
                   :margin-top "1em"}}
     [uppy/Dashboard {:endpoint (str "/api/import-articles/pdfs/" project-id)
                      :on-complete #(dispatch [:on-add-source project-id])
                      :project-id project-id}]]))

(defn ImportPDFZipsView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [ui/UploadButton
      (str "/api/import-articles/pdf-zip/" project-id)
      #(dispatch [:on-add-source project-id])
      "Upload Zip File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))
      {}
      :post-error-text "Try editing your file to fit the upload instructions above
or contact us at info@insilica.co with a copy of your zip file."]]))

(defn ImportJSONView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:p "Please upload a JSON file with the following schema:"
      [:div
       [:code "{ \"articles\": [ {\"title\": \"...\", \"description\": \"...\"}, ... ] }"]]]
     [ui/UploadButton
      (str "/api/import-articles/json/" project-id)
      #(dispatch [:on-add-source project-id])
      "Upload JSON File..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))
      {} :post-error-text "Try editing your file to fit the upload instructions above or
contact us at info@insilica.co with a copy of your JSON file."]]))

(defn ImportRISView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui
     [:h3 "2. Import an RIS / RefMan file"
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
     [:p "Having difficulties? We recommend using the free citation manager "
      [:a {:href "https://zotero.org" :target "_blank"} "Zotero"] "."]
     [ui/UploadButton
      (str "/api/import-articles/ris/" project-id)
      #(dispatch [:on-add-source project-id])
      "Upload RIS file..."
      (cond-> "fluid"
        (any-source-processing?) (str " disabled"))
      {}
      :post-error-text "Try processing your file with Zotero using the
'Having difficulties importing your RIS file?' instructions above."]]))

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

(defn ReImportSource [source]
  (when (> (:new-articles-available source) 0)
    (let [project-id @(subscribe [:active-project-id])]
      [:div.ui.blue.mt-2
       [:span.ui.text.blue
        (:new-articles-available source) " new articles found. "]
       [:div.mt-1
        [:div.ui.mini.button.blue
         {:on-click #(dispatch [:action [:sources/re-import project-id (:source-id source)]])}
         [:i.download.icon]
         " Import new articles"]]])))

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
      [:div.seven.wide.middle.aligned.left.aligned.column
       {:style {:padding-right "0.25rem"}}
       [:div.ui.large.label.source-type {:data-name source-type}
        (str source-type)]
       (when enabled
         [SourceArticlesLink source-id])]
      [:div.nine.wide.right.aligned.middle.aligned.column
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

(defn poll-project-sources [project-id]
  (when (not @polling-sources?)
    (reset! polling-sources? true)
    (dispatch [:fetch [:project/sources project-id]])
    (let [sources (subscribe [:project/sources])
          source-ids (subscribe [:project/source-ids])
          source-updating?
          (fn [source-id]
            (or (action/running? [:sources/delete project-id source-id])
                (let [[source] (filter #(= (:source-id %) source-id) @sources)
                      {:keys [importing-articles? deleting?]} (:meta source)]
                  (or (and (true? importing-articles?)
                           (not (source-import-timed-out? source)))
                      (true? deleting?)))))
          any-source-updating? #(some source-updating? @source-ids)]
      (util/continuous-update-until
       (fn [] (dispatch [:fetch [:project/sources project-id]]))
       (fn [] (not (any-source-updating?)))
       (fn []
         (reset! polling-sources? false)
         (dispatch [:reload [:project project-id]]))
       600))
    nil))

(reg-event-fx :poll-project-sources [trim-v]
              (fn [_ [project-id]]
                (-> #(poll-project-sources project-id)
                    (js/setTimeout 200))
                {}))

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
          (poll-project-sources project-id))
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
               [source-view/EditJSONView
                {:source (subscribe [:project/sources source-id])
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
                     " unique " (article-or-articles unique-articles-count)
                     " "
                     [ReImportSource source]])
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
                   [EditSourceModal source]
                   (when (zero? labeled-article-count)
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
                    [:div.ui.small.active.loader]]))]]
         (when-not (str/blank? (:notes source))
           [:div.ui.segment
            [:h4 "Notes"]
            [:div (util/ellipsize (:notes source) 400)]])]))))

(defn ProjectSourcesList []
  (let [sources @(subscribe [:project/sources])]
    (when-not (empty? sources)
      [:div#project-sources
       [:div
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
         (str "mailto:info@insilica.co?"
              "subject=How can I use ClinicalTrials.gov with sysrev?"
              "&body=Hi, I would like to know more about using"
              " ClinicalTrials.gov in sysrev to conduct reviews."
              " Please let me know how I can enable this feature. Thanks!")
         :target "_blank"} " contact us"]"."]])

(defn CustomDatasource []
  [:div.ui.segment {:style {:margin-left "auto"
                            :margin-right "auto"
                            :max-width "600px"}}
   [:b "Need to review something else? " [:a {:href "/managed-review"} "Talk to us"]
    " about integrating unique datasources including JSON, XML, and more."]])

(defn DatasourceIcon [{:keys [text value name]}]
  (let [active? (= @(subscribe [:add-articles/import-tab]) value)]
    [:div.datasource-item {:on-click #(dispatch-sync [:add-articles/import-tab value])
                           :class (css [active? "active"])
                           :style {:display "inline-block"
                                   :text-align "center"
                                   :margin "1em 1em 0 1em"}}
     [:div {:style {:flex "0 0 120px" :cursor "pointer"}}
      [Icon {:name name :size "big"}]
      [:p {:style {:margin-top "1em"}} text]]]))

(defn DatasourceIconList [options]
  [:div {:style {:padding-bottom 20}}
   (for [option options] ^{:key (:value option)}
     [DatasourceIcon option])])

(defn ImportArticlesView []
  (let [active-tab (subscribe [:add-articles/import-tab])
        beta-access? (or (not= js/window.location.hostname "sysrev.com")
                         (boolean
                          (some #{@(subscribe [:self/email])}
                                #{"amarluniwal@gmail.com"
                                  "geoffreyweiner@gmail.com"
                                  "james@insilica.co"
                                  "tom@insilica.co"
                                  "jeff@insilica.co"
                                  "tj@insilica.co"
                                  "g.callegaro@lacdr.leidenuniv.nl"})))]
    [:div#import-articles {:style {:margin-bottom "1em"}}
     [:div
      [:h3 "1. Select a document source"]
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
                           (when beta-access?
                             {:value :fda-drugs-docs
                              :text "Drugs@FDA Documents (beta)"
                              :name "at"})
                           {:value :json
                            :text "JSON file"
                            :name "file outline"}
                           {:value :custom
                            :text "Custom Datasource"
                            :name "database"}]]
      (when @active-tab
        (condp =  @active-tab
          :pubmed    [:div [:h3 "2. Search pubmed to review medical abstracts"] [pubmed/SearchBar]]
          :pmid      [:div [:h3 "2. Upload a file with pubmed ids (one per line)"] [ImportPMIDsView]]
          :endnote   [:div [:h3 "2. Upload an Endnote XML file export"] [ImportEndNoteView]]
          :pdfs      [:div [:h3 "2. Import PDF files"] [ImportPDFsView]]
          :json      [:div [:h3 "2. JSON file"] [ImportJSONView]]
          :pdf-zip   [:div [:h3 "2. Upload a zip file containing PDFs.
An article entry will be created for each PDF."] [ImportPDFZipsView]]
          :ris-file  [ImportRISView]
          :ctgov (if-not beta-access?
                   [EnableCTNotice]
                   [:div
                    [:h3 "2. Search and import clinicaltrials.gov documents."]
                    [ctgov/SearchBar]])
          :fda-drugs-docs [:div
                           [:h3 "2. Search and import Drugs@FDA application documents."]
                           [fda-drugs-docs/SearchBar]]
          :custom [CustomDatasource]
          nil))
      (condp =  @active-tab
        :pubmed [pubmed/SearchActions (any-source-processing?)]
        :ctgov  [ctgov/SearchActions (any-source-processing?)]
        :fda-drugs-docs  [fda-drugs-docs/SearchActions (any-source-processing?)]
        nil)]
     (condp =  @active-tab
       :pubmed [pubmed/SearchResultsContainer]
       :ctgov  [ctgov/SearchResultsContainer]
       :fda-drugs-docs  [fda-drugs-docs/SearchResultsContainer]
       nil)]))

(defn DocumentImport []
  (let [project-id @(subscribe [:active-project-id])
        visible? @(subscribe [::add-documents-visible project-id])
        sources @(subscribe [:project/sources])
        show-filters? (= :ctgov @(subscribe [:add-articles/import-tab]))]
    [:div {:style {:padding-bottom 10}}
     [(if show-filters? :div.ui.grid>div.row :div)
      (when show-filters?
        [:div.column.filters-column.five.wide
         [ctgov/SearchFilters]])
      [(if show-filters? :div.column.content-column.eleven.wide :div)
       [Button {:id "enable-import"
                :size "huge" :positive true
                :disabled visible?
                :on-click (fn []
                            (dispatch [::add-documents-visible true])
                            (dispatch-sync [:add-articles/import-tab nil]))
                :style {:display "inline"}}
        "Add Documents"]
       (when (empty? sources)
         [:h3.inline {:style {:margin-left "0.75rem"}}
          "Add documents to get started."])
       (when visible?
         [:div.ui.segment.raised
          [:div
           [Button {:id "enable-import-dismiss"
                    :style {:float "right"}
                    :size "small" :color "red"
                    :on-click (fn []
                                (dispatch [::add-documents-visible false])
                                (dispatch-sync [:add-articles/import-tab nil]))}
            "dismiss"]
           [:h1 {:style {:padding-top 0 :margin-top 0}} "Adding Documents"]
           [ImportArticlesView]]])]]]))

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
