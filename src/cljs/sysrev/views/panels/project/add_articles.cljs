(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [dispatch subscribe reg-fx reg-event-fx trim-v]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :refer [continuous-update-until]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.pubmed :as pubmed :refer [SearchPanel]]))

(def initial-state {:pubmed-visible? false})
(def state (r/atom initial-state))

(reg-event-fx
 :add-articles/reset-state!
 [trim-v]
 (fn [_]
   (reset! state initial-state)
   {}))

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

(def-action :sources/delete-source
  :uri (fn [] "/api/delete-source")
  :content (fn [source-id]
             {:source-id source-id})
  :process
  (fn [_ _ {:keys [success] :as result}]
    (if success
      {:dispatch-n
       (list
        [:fetch [:project/project-sources]])})))

(defn DeleteArticleSource
  [source-id]
  [:a {:href "#"
       :on-click (fn [event]
                   (.preventDefault event)
                   (dispatch
                    [:action [:sources/delete-source source-id]]))}
   "Delete Source"])

(defn PubMedSearchSource
  [state]
  (let [source-updating? (fn [source-id]
                           (->> @(subscribe [:project/sources])
                                (filter #(= (:source-id %)
                                            source-id))
                                first :meta :importing-articles?))]
    (fn [source]
      (let [{:keys [meta source-id article-count labeled-article-count]} source
            {:keys [importing-articles? search-term]} meta]
        [:div.project-source.ui.segment
         [:div.ui.grid
          [:div.eleven.wide.column
           [:h3 "PubMed Search Term: "
            search-term]]
          [:div.five.wide.column.right.aligned
           ;; when articles are still loading
           (when importing-articles?
             (continuous-update-until #(dispatch [:fetch [:project/project-sources]])
                                      #(not (source-updating? source-id))
                                      1000)
             [:div.ui.active.loader [:div.ui.loader]])
           ;; when articles have been imported
           (when-not importing-articles?
             [:div [:div (str (.toLocaleString labeled-article-count)
                              " of "
                              (.toLocaleString article-count) " articles reviewed")]
              (when (<= labeled-article-count 0)
                [DeleteArticleSource source-id])])]]]))))

(defn ProjectSources
  [state]
  (fn [props]
    (let [sources (subscribe [:project/sources])]
      [:div#project-sources
       (doall (map (fn [source]
                     (condp = (get-in source [:meta :source])
                       "PubMed search"
                       ^{:key (:source-id source)}
                       [PubMedSearchSource source]))
                   (sort-by :source-id @sources)))])))

(defn AddArticles
  []
  (fn [props]
    [:div
     [ProjectSources state]
     [:br]
     [AddPubMedArticles state]]))

(defmethod panel-content [:project :project :add-articles] []
  (fn [child]
    [:div.project-content
     [AddArticles]]))
