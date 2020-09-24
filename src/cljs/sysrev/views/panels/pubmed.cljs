(ns sysrev.views.panels.pubmed
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.views.pubmed :as pubmed]
            [sysrev.macros :refer-macros [def-panel]]))

(defn- SearchPanel []
  [:div.search-panel
   [pubmed/SearchBar]
   [pubmed/SearchActions]
   [pubmed/SearchResultsContainer]])

(def-panel {:uri "/pubmed-search"
            :on-route (dispatch [:set-active-panel pubmed/panel])
            :panel pubmed/panel
            :content [SearchPanel]})
