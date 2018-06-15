(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [dispatch subscribe reg-fx reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [cljs-time.core :as t]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :refer [continuous-update-until]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.pubmed :as pubmed :refer [SearchPanel]]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.upload :refer [upload-container basic-text-button]]
            [sysrev.views.components :as ui]))

(def panel [:project :project :add-articles])

(def initial-state {:read-only-message-closed? false})

(defonce state (r/cursor app-db [:state :panels panel]))
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
  (fn [_ [project-id _ _] {:keys [success] :as result}]
    (if success
      {:dispatch-n
       (list [:reload [:review/task project-id]]
             [:reload [:project project-id]])})))

(defn admin?
  []
  (or @(subscribe [:member/admin?])
      @(subscribe [:user/admin?])))

(defn plural-or-singular
  "Return the singular form of string when item-count is one, return plural otherwise"
  [item-count string]
  (if (= item-count 1)
    string
    (str string "s")))

(defn article-or-articles
  "Return either the singular or plural form of article"
  [item-count]
  (plural-or-singular item-count "article"))

(defn ImportEndNoteView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:h4.ui.dividing.header "Import from EndNote XML file"]
     [:div.upload-container
      [:h4 (str "To create an EndNote XML file in EndNote, go to File > Export."
                " Select 'XML' in 'Save file as type'")]
      [upload-container
       basic-text-button
       "/api/import-articles-from-endnote-file"
       #(do (dispatch [:reload [:project/sources project-id]]))
       "Upload File..."]]]))

(defn ImportPMIDsView []
  (let [project-id @(subscribe [:active-project-id])]
    [:div
     [:h4.ui.dividing.header "Import from PMIDs in text file"]
     [:div.upload-container
      [:h4 "Upload a plain text file with each PubMed ID (PMID) on a separate line"]
      [upload-container
       basic-text-button
       "/api/import-articles-from-file"
       #(do (dispatch [:reload [:project/sources project-id]]))
       "Upload File..."]]]))

(defn ImportPubMedView []
  (pubmed/ensure-state)
  (let [current-search-term (r/cursor pubmed/state [:current-search-term])]
    [:div
     [:h4.ui.dividing.header "Import from PubMed search"]
     [SearchPanel pubmed/state]]))

(defn DeleteArticleSource
  [source-id]
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.tiny.orange.basic.icon.button.delete-button
     {:on-click
      (fn [] (do (dispatch
                  [:action [:sources/delete project-id source-id]])
                 (js/setTimeout
                  #(dispatch [:fetch [:project/sources project-id]])
                  100)))}
     "Delete " [:i.times.circle.icon]]))

(defn ToggleArticleSource
  [source-id enabled?]
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.tiny.button
     {:on-click
      (fn []
        (do (dispatch [:action [:sources/toggle-source
                                project-id source-id (not enabled?)]])
            (js/setTimeout
             #(dispatch [:fetch [:project/sources project-id]])
             100)))}
     (if enabled?
       "Enabled"
       "Disabled")]))

(defn meta->source-name-vector
  [meta]
  (let [source (:source meta)]
    (condp = source
      "PubMed search"
      ["PubMed Search" (str "\"" (:search-term meta) "\"")]

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

      ["Unknown Source" nil])))

(defn SourceInfoView [{:keys [source] :as meta}]
  (let [[source-type import-label]
        (meta->source-name-vector meta)]
    [:div.ui.middle.aligned.grid.source-info>div.row
     [:div.eight.wide.column.left.aligned
      [:div.ui.large.label (str source-type)]]
     [:div.eight.wide.column.right.aligned
      (when import-label
        [:div.import-label.ui.large.basic.label (str import-label)])]]))

(defn source-name
  "Given a source-id, return the source name vector"
  [source-id]
  (let [sources (subscribe [:project/sources])]
    (->> @sources
         (filter #(= source-id (:source-id %)))
         first
         :meta
         (meta->source-name-vector))))

(defn- source-import-timed-out? [source]
  (let [{:keys [meta source-id date-created
                article-count labeled-article-count]} source
        {:keys [importing-articles? deleting?]} meta]
    (and (true? importing-articles?)
         (t/within? {:start (t/epoch)
                     :end (t/minus (t/now) (t/minutes 10))}
                    date-created))))

(defonce polling-sources? (r/atom false))

(defn poll-project-sources [project-id source-id]
  (when (not @polling-sources?)
    (reset! polling-sources? true)
    (dispatch [:fetch [:project/sources project-id]])
    (let [sources (subscribe [:project/sources])
          delete-running?
          (subscribe
           [:action/running? [:sources/delete project-id source-id]])
          source-updating?
          (fn [source-id]
            (or @delete-running?
                (->>
                 @sources
                 (filter #(= (:source-id %) source-id))
                 first
                 ((fn [source]
                    (let [{:keys [importing-articles?
                                  deleting?]}
                          (:meta source)]
                      (or (and (true? importing-articles?)
                               (not (source-import-timed-out? source)))
                          (true? deleting?))))))))]
      (continuous-update-until #(dispatch [:fetch [:project/sources project-id]])
                               #(not (source-updating? source-id))
                               #(do (reset! polling-sources? false)
                                    (dispatch [:reload [:project project-id]]))
                               1500))))

(defn ArticleSource [source]
  (let [project-id @(subscribe [:active-project-id])
        {:keys [meta source-id date-created
                article-count labeled-article-count
                enabled]} source
        enabled? enabled
        {:keys [importing-articles? deleting?]} meta
        polling? @polling-sources?
        delete-running? @(subscribe
                          [:action/running? [:sources/delete project-id source-id]])
        timed-out? (source-import-timed-out? source)]
    (when (or (and (true? importing-articles?) (not timed-out?))
              deleting? delete-running?)
      (poll-project-sources project-id source-id)
      nil)
    [:div.project-source
     [:div.ui.top.attached.segment
      [SourceInfoView meta]]
     [:div.ui.bottom.attached.segment
      [:div.ui.middle.aligned.grid>div.row
       (cond
         (= (:source meta) "legacy")
         (list
          [:div.eight.wide.column.left.aligned.reviewed-count
           {:key :reviewed-count}
           [:div
            (str (.toLocaleString labeled-article-count) " of "
                 (.toLocaleString article-count) " "
                 (article-or-articles article-count) " reviewed")]]
          [:div.eight.wide.column.right.aligned
           {:key :buttons}
           nil])

         ;; when source is currently being deleted
         (or deleting? delete-running?)
         (list
          [:div.eight.wide.column.left.aligned
           {:key :deleting}
           [:div "Deleting source..."]]
          [:div.six.wide.column
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
         (and (true? importing-articles?) polling?
              article-count (> article-count 0))
         (list
          [:div.eight.wide.column.left.aligned.loaded-count
           {:key :loaded-count}
           [:div
            (str (.toLocaleString article-count) " "
                 (article-or-articles article-count) " loaded")]]
          [:div.six.wide.column
           {:key :placeholder}]
          [:div.two.wide.column.right.aligned
           {:key :loader}
           [:div.ui.small.active.loader]])

         ;; when articles have been imported
         (and (false? importing-articles?)
              labeled-article-count article-count)
         (list
          [:div.source-description.eight.wide.column.left.aligned
           {:key :reviewed-count}
           [:div
            (str (.toLocaleString labeled-article-count) " of "
                 (.toLocaleString article-count) " "
                 (article-or-articles article-count) " reviewed")]
           ;; put unique
           (let [unique-articles-count (:unique-articles-count source)]
             (when-not (nil? unique-articles-count)
               [:span unique-articles-count " unique " (article-or-articles unique-articles-count)]))
           ;; put overlap
           (let [overlap (:overlap source)
                 non-empty-overlap (filter #(> (:count %) 0) overlap)]
             [:div
              (when-not (empty? non-empty-overlap)
                (doall
                 (map (fn [{:keys [count overlap-source-id]} overlap-map]
                        ^{:key (gensym (:overlap-source-id overlap-map))}
                        [:div
                         [:span
                          (str
                           count " " (article-or-articles count) " shared with "
                           (let [name (source-name overlap-source-id)]
                             (str (first name) " " (second name))))]
                         [:br]])
                      non-empty-overlap)))])]
          (when (admin?)
            [:div.eight.wide.column.right.aligned
             {:key :buttons}
             [ToggleArticleSource source-id enabled?]
             (when (and (<= labeled-article-count 0))
               [DeleteArticleSource source-id])]))
         :else
         (list
          [:div.eight.wide.column.left.aligned
           {:key :import-status}
           "Starting import..."]
          [:div.six.wide.column
           {:key :placeholder}]
          [:div.two.wide.column.right.aligned
           {:key :loader}
           [:div.ui.small.active.loader]]))]]]))

(defn ProjectSourcesList []
  (ensure-state)
  (let [sources @(subscribe [:project/sources])
        article-count (:total @(subscribe [:project/article-counts]))]
    [:div#project-sources.ui.segment
     [:h4.ui.dividing.header
      "Article Sources"]
     (if (empty? sources)
       (if (and article-count (> article-count 0))
         [:h4 "No article sources added yet"]
         [:h4 "No articles imported yet"])
       [:div.project-sources-list
        (doall (map (fn [source]
                      ^{:key (:source-id source)}
                      [ArticleSource source])
                    (reverse (sort-by :source-id sources))))])]))

(defn ImportArticlesView []
  (ensure-state)
  (let [import-tab (r/cursor state [:import-tab])
        active-tab (or @import-tab :pubmed)]
    [:div#import-articles.ui.segment
     [:h4.ui.dividing.header
      "Import Articles"]
     [ui/tabbed-panel-menu
      [{:tab-id :pubmed
        :content "PubMed Search"
        :action #(reset! import-tab :pubmed)}
       {:tab-id :pmid
        :content "PMIDs"
        :action #(reset! import-tab :pmid)}
       {:tab-id :endnote
        :content "EndNote XML"
        :action #(reset! import-tab :endnote)}]
      active-tab
      "import-source-tabs"]
     (case active-tab
       :pubmed   [ImportPubMedView]
       :pmid     [ImportPMIDsView]
       :endnote  [ImportEndNoteView])]))

(defn ProjectSourcesPanel []
  (ensure-state)
  (let [read-only-message-closed? (r/cursor state [:read-only-message-closed?])]
    [:div
     (when (admin?)
       [ImportArticlesView])
     [ReadOnlyMessage
      "Managing sources is restricted to project administrators."
      read-only-message-closed?]
     [ProjectSourcesList]]))

(defmethod panel-content panel []
  (fn [child]
    [:div#add-articles.project-content
     [ProjectSourcesPanel]]))
