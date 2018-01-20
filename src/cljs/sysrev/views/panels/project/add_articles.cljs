(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [dispatch subscribe reg-fx reg-event-fx trim-v]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :refer [continuous-update-until]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.pubmed :as pubmed :refer [SearchPanel]]
            [sysrev.views.upload :refer [upload-container basic-text-button]]))

(def initial-state {:pubmed-visible? false})
(def state (r/atom initial-state))

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

(defn ArticleSource
  [state]
  (let [source-updating? (fn [source-id]
                           (->> @(subscribe [:project/sources])
                                (filter #(= (:source-id %)
                                            source-id))
                                first :meta :importing-articles?))]
    (fn [source]
      (let [{:keys [meta source-id article-count labeled-article-count]} source
            {:keys [importing-articles?]} meta]
        [:div.project-source.ui.segment
         [:div.ui.grid
          [:div {:class (str (if-not importing-articles?
                               "eleven"
                               "fifteen") " wide column")}
           [MetaDisplay meta]]
          ;; when articles are still loading
          (when importing-articles?
            (continuous-update-until #(dispatch [:fetch [:project/sources]])
                                     #(not (source-updating? source-id))
                                     #(dispatch [:reload [:project]])
                                     1000)
            [:div.one.wide.column.right.aligned
             [:div.ui.active.loader [:div.ui.loader]]])
          ;; when articles have been imported
          (when-not importing-articles?
            [:div.five.wide.column.right.aligned
             [:div [:div (str (.toLocaleString labeled-article-count)
                              " of "
                              (.toLocaleString article-count) " articles reviewed")]
              (when (<= labeled-article-count 0)
                [DeleteArticleSource source-id])]])]]))))

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
    [:div
     [ProjectSources state]
     [:br]
     [AddFileUploadArticles state]
     [:br]
     [AddPubMedArticles state]]))

(defmethod panel-content [:project :project :add-articles] []
  (fn [child]
    [:div.project-content
     [AddArticles]]))
