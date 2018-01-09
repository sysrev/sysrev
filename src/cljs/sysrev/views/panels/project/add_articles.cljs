(ns sysrev.views.panels.project.add-articles
  (:require [reagent.core :as r]
            [sysrev.views.base :refer [panel-content]]))

(defn AddArticles
  []
  (fn []
    [:div.project-content
     [:a "Add articles from a PubMed Search"]]))

(defmethod panel-content [:project :project :add-articles] []
  (fn [child]
    [AddArticles]))
