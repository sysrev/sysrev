(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [dispatch subscribe]]
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

(defn ProjectSource
  [state]
  (fn [source]
    (let [metadata (:meta source)]
      [:div.project-source.ui.segment
       [:h3 "Source " (:source metadata) " "
        (when (:importing-articles? metadata)
          [:div.ui.active.loader.inline [:div.ui.loader]])]])))

(defn ProjectSources
  [state]
  (fn [props]
    (let [sources (subscribe [:project/sources])]
      [:div#project-sources
       (doall (map (fn [source]
                     ^{:key (:source-id source)}
                     [ProjectSource source])
                   @sources))])))

(defn AddArticles
  []
  (fn [props]
    [:div
     [ProjectSources state]
     [AddPubMedArticles state]]))

(defmethod panel-content [:project :project :add-articles] []
  (fn [child]
    [:div.project-content
     [AddArticles]]))
