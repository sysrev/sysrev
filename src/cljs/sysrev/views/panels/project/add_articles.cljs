(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [dispatch subscribe reg-fx reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :refer [continuous-update-until]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.pubmed :as pubmed :refer [SearchPanel]]
            [sysrev.views.upload :refer [upload-container basic-text-button]]))

(def panel [:project :project :add-articles])

(def initial-state {:pubmed-visible? false})
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

(defn AddFileUploadArticles
  "A panel for uploading PMIDs via file"
  [state]
  (let []
    (fn [props]
      [:div#upload-file-panel
       [:div.ui.segment
        [:h3.ui.dividing.header
         "Add Articles from File"]
        [:div.upload-container
         [upload-container
          basic-text-button "/api/import-articles-from-file"
          (fn []
            (.log js/console "file uploaded")
            (dispatch [:reload [:project/sources]]))
          "Upload File"]]]])))

(defn AddPubMedArticles
  [state]
  (pubmed/ensure-state)
  (let [pubmed-visible? (r/cursor state [:pubmed-visible?])
        current-search-term (r/cursor pubmed/state [:current-search-term])]
    (fn [props]
      [:div
       (when-not @pubmed-visible?
         [:a {:href "#"
              :on-click (fn [event]
                          (.preventDefault event)
                          (reset! current-search-term nil)
                          (reset! pubmed-visible? true))}
          "Add articles from a PubMed Search"])
       (when @pubmed-visible?
         [:div
          [:a {:href "#"
               :on-click (fn [event]
                           (.preventDefault event)
                           (reset! pubmed-visible? false))}
           "Quit Searching"]
          [:br]
          [:br]
          [SearchPanel pubmed/state]])])))

(defn DeleteArticleSource
  [source-id]
  [:a {:href "#"
       :on-click (fn [event]
                   (.preventDefault event)
                   (dispatch
                    [:action [:sources/delete source-id]]))}
   "Delete Source"])

(defn MetaDisplay
  [meta]
  (let [{:keys [source]} meta]
    (condp = source
      "PubMed search"
      [:h3 "PubMed Search Term: "
       (:search-term meta)]
      "PMID file"
      [:h3 "PMIDs from file: " (:filename meta)]
      "PMID vector"
      [:h3 "PMIDs uploaded via web-api"]
      "fact"
      [:h3 "PMIDs taken from facts"]
      [:h3 "Unknown Source"])))

(defonce polling-sources? (r/atom false))

(defn poll-project-sources [source-id]
  (when (not @polling-sources?)
    (reset! polling-sources? true)
    (dispatch [:fetch [:project/sources]])
    (let [source-updating? (fn [source-id]
                             (->> @(subscribe [:project/sources])
                                  (filter #(= (:source-id %)
                                              source-id))
                                  first :meta :importing-articles?))]
      (continuous-update-until #(dispatch [:fetch [:project/sources]])
                               #(not (source-updating? source-id))
                               #(do (reset! polling-sources? false)
                                    (dispatch [:reload [:project]]))
                               1500))))

(defn ArticleSource
  [state]
  (fn [source]
    (let [{:keys [meta source-id article-count labeled-article-count]} source
          {:keys [importing-articles?]} meta
          polling? @polling-sources?
          deleting? @(subscribe
                      [:action/running? [:sources/delete source-id]])]
      (when importing-articles?
        (poll-project-sources source-id)
        nil)
      [:div.project-source.ui.segment
       [:div.ui.grid
        [:div.eleven.wide.column
         [MetaDisplay meta]]
        (cond
          deleting?
          (list
           [:div.four.wide.column.right.aligned
            {:key :deleting}
            [:div "Deleting source..."]]
           [:div.one.wide.column.right.aligned
            {:key :loader}
            [:div.ui.active.loader [:div.ui.loader]]])

          ;; when articles are still loading
          (and importing-articles? polling? article-count)
          (list
           [:div.four.wide.column.right.aligned
            {:key :loaded-count}
            [:div (str (.toLocaleString article-count) " articles loaded")]]
           [:div.one.wide.column.right.aligned
            {:key :loader}
            [:div.ui.active.loader [:div.ui.loader]]])

          ;; when articles have been imported
          (and (false? importing-articles?)
               labeled-article-count article-count)
          [:div.five.wide.column.right.aligned
           [:div [:div (str (.toLocaleString labeled-article-count)
                            " of "
                            (.toLocaleString article-count) " articles reviewed")]
            (when (<= labeled-article-count 0)
              [DeleteArticleSource source-id])]]

          :else
          (list
           [:div.four.wide.column.right.aligned
            {:key :placeholder}]
           [:div.one.wide.column.right.aligned
            {:key :loader}
            [:div.ui.active.loader [:div.ui.loader]]]))]])))

(defn ProjectSources
  [state]
  (fn [props]
    (let [sources (subscribe [:project/sources])]
      [:div#project-sources
       (doall (map (fn [source]
                     ^{:key (:source-id source)}
                     [ArticleSource source])
                   (sort-by :source-id @sources)))])))

(defn AddArticles
  []
  (fn [props]
    (ensure-state)
    [:div
     [ProjectSources state]
     [:br]
     [AddFileUploadArticles state]
     [:br]
     [AddPubMedArticles state]]))

(defmethod panel-content panel []
  (fn [child]
    [:div.project-content
     [AddArticles]]))
