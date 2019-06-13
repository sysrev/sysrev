(ns sysrev.views.search.core
  (:require [ajax.core :refer [GET]]
            [goog.uri.utils :as uri-utils]
            [reagent.core :as r]
            [sysrev.base :refer [active-route]]
            [sysrev.markdown :as markdown]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer [Form Input Loader Divider]]))

(def ^:private panel [:search])

(def state (r/atom {:search-results {}
                    :search-value ""}))

(defn project-search [q]
  (let [search-results (r/cursor state [:search-results])]
    (swap! search-results assoc q :retrieving)
    (GET "/api/search"
         {:params {:q q}
          :handler (fn [response]
                     (swap! search-results assoc q (get-in response [:result :results])))
          :error-handler (fn [response]
                           (swap! search-results assoc q "There was an error retrieving results, please try again"))})))

(defn SiteSearch []
  (let [search-value (r/cursor state [:search-value])]
    (r/create-class
     {:reagent-render
      (fn [args]
        [Form {:on-submit (fn [e]
                            (project-search @search-value)
                            (nav-scroll-top "/search" :params {:q @search-value}))}
         [Input {:placeholder "Search Sysrev"
                 :on-change (fn [e value]
                              (let [input-value (-> value
                                                     (js->clj :keywordize-keys true)
                                                     :value)]
                                 (reset! search-value input-value)))
                  :id "search-sysrev-bar"
                  :value @search-value}]])})))

(defn ProjectSearchResult
  [{:keys [project-id description name]}]
  [:div
   [:a {:href (project-uri project-id)} [:h3 name]]
   [:p (-> description markdown/create-markdown-html markdown/html->string)]
   [Divider]])

(defn SearchResults [{:keys [q]}]
  (reset! (r/cursor state [:search-value]) q)
  (project-search q)
  (r/create-class
   {:reagent-render
    (fn [{:keys [q]}]
      (let [search-results (r/cursor state [:search-results q])
            projects (r/cursor search-results [:projects])]
        [:div
         ;;[:h1 "Results for " q ":"]
         (if (= @search-results :retrieving)
           [Loader {:active true}]
           (if-not (empty? @projects)
             (doall
              (for [project @projects]
                ^{:key (:project-id project)}
                [ProjectSearchResult project]))
             [:div
              [:h3 "No Results Found"]]))]))}))

(defmethod panel-content [:search] []
  (fn [child]
    [:div
     ;;[SiteSearch]
     [SearchResults {:q (uri-utils/getParamValue @active-route "q")}]]))



