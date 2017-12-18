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
  (let [current-search-term (r/atom nil)]
    (fn [props]
      (let [search-results (subscribe [:pubmed/search-term-result @current-search-term])]
        [:div.create-project
         [:div.ui.segment
          [:h3.ui.dividing.header
           "Create a New Project"]
          [:form {:on-submit (fn [event]
                               (.preventDefault event)
                               (dispatch [:fetch [:pubmed-query @current-search-term]]))}
           [:div.ui.fluid.icon.input
            [:input {:type "text"
                     :placeholder "Search..."
                     :on-change (fn [event]
                                  (reset! current-search-term (-> event
                                                                  (aget "target")
                                                                  (aget "value")))
                                  )}]
            [:i.inverted.circular.search.link.icon
             {:on-click (fn [event]
                          (.preventDefault event)
                          (dispatch [:fetch [:pubmed-query @current-search-term]]))}]]]
          [:h3
           ;; we are currently missing the concept of
           ;; 'the search has been done, but the results have
           ;;  not been fetched yet'
           (cond
             ;; the search term hasn't been populated
             (nil? @current-search-term)
             nil
             ;; the search term is populated and there
             ;; are results to be displayed
             (and (not (nil? @current-search-term))
                  (not (empty? @search-results)))
             (str @search-results)
             ;; the search term is populated but there
             ;; isn't anything to display
             ;; WARNING: This could also occur while results are being
             ;; fetched!
             (and (not (nil? @current-search-term))
                  (empty? @search-results))
             "No documents match your search terms"
             )]]]))))

(defmethod panel-content [:create-project] []
  (fn [child]
    [search-panel]))
