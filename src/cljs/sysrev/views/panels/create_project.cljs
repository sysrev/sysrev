(ns sysrev.views.panels.create-project
  (:require
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.panels.project.main :refer [project-header]]
   [sysrev.util :refer [full-size? mobile?]]
   [sysrev.shared.util :refer [in?]]))

(defn search-panel []
  "A panel for search pubmed"
  (let [current-search-term (r/atom nil)
        on-change-search-term (r/atom nil)]
    (fn [props]
      (let [search-results (subscribe [:pubmed/search-term-result @current-search-term])
            fetch-results (fn [event]
                            (.preventDefault event)
                            (reset! current-search-term @on-change-search-term)
                            ;; fetch only if results for the search term don't already exist
                            (when (nil? @(subscribe [:pubmed/search-term-result @current-search-term]))
                              (dispatch [:fetch [:pubmed-query @current-search-term]])))]
        [:div.create-project
         [:div.ui.segment
          [:h3.ui.dividing.header
           "Create a New Project"]
          [:form {:on-submit fetch-results}
           [:div.ui.fluid.icon.input
            [:input {:type "text"
                     :placeholder "Search..."
                     :on-change (fn [event]
                                  (reset! on-change-search-term (-> event
                                                                    (aget "target")
                                                                    (aget "value"))))}]
            [:i.inverted.circular.search.link.icon
             {:on-click fetch-results}]]]
          [:h3
           (cond
             ;; the search term hasn't been populated
             (nil? @current-search-term)
             nil
             ;; the user has cleared the search term
             (empty? @current-search-term)
             nil
             ;; the search term is not nil
             ;; and the search-results are empty
             ;; and the term is not being loaded
             (and (not (nil? @current-search-term))
                  (empty? @search-results)
                  (not @(subscribe [:loading? [:pubmed-query @current-search-term]])))
             "No documents match your search terms"
             ;; the search term is populated and there
             ;; are results to be displayed
             (and (not (nil? @current-search-term))
                  (not (empty? @search-results)))
             (str @search-results))]]]))))

(defmethod panel-content [:create-project] []
  (fn [child]
    [search-panel]))
