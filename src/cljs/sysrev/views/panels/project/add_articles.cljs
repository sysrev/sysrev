(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [dispatch subscribe]]
            [sysrev.util :refer [continuous-update-until]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.pubmed :as pubmed :refer [SearchPanel]]))

(def state (r/atom {:pubmed-visible? false}))

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
                           (.log js/console "I changed the status")
                           (reset! pubmed-visible? false))}
           "Quit Searching"]
          [:br]
          [:br]
          [SearchPanel pubmed/state]])])))

(defn PubMedSearchSource
  [state]
  (let [source-updating? (fn [source-id]
                           (->> @(subscribe [:project/sources])
                                (filter #(= (:source-id %)
                                            source-id))
                                first :meta :importing-articles?))]
    (fn [source]
      (let [metadata (:meta source)]
        [:div.project-source.ui.segment
         [:div.ui.grid
          [:div.fourteen.wide.column
           [:h3 "PubMed Search Term: "
            (:search-term metadata)]]
          [:div.two.wide.column
           (when (:importing-articles? metadata)
             (continuous-update-until #(dispatch [:fetch [:project/project-sources]])
                                      #(not (source-updating? (:source-id source)))
                                      1000)
             [:div.ui.active.loader [:div.ui.loader]])
           #_         (when-not (:import-articles? metadata)
                        ;; we will write the code on the server
                        ;; to return the article-count col
                        ;; in sysrev.db.project/project-sources
                        ;; that will include joins
                        ;; https://stackoverflow.com/questions/4535782/select-count-of-rows-in-another-table-in-a-postgres-select-statement
                        (str (.toLocaleString (:article-count metadata)) " articles"))
           ]]]))))

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
