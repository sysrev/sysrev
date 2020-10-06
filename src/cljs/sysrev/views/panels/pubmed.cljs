(ns sysrev.views.panels.pubmed
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.views.pubmed :as pubmed]
            [sysrev.macros :refer-macros [def-panel]]))

(defn- SearchPanel []
  [:div.search-panel
   [pubmed/SearchBar]
   [pubmed/SearchActions]
   [pubmed/SearchResultsContainer]])

(def-panel :uri "/pubmed-search" :panel pubmed/panel
  :on-route (dispatch [:set-active-panel pubmed/panel])
  :content [SearchPanel])
