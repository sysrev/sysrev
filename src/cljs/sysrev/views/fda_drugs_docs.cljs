(ns sysrev.views.fda-drugs-docs
  (:require [clojure.string :as str]
            [medley.core :as medley]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.datapub :as datapub]
            [sysrev.macros :refer-macros [setup-panel-state]]
            [sysrev.shared.fda-drugs-docs :as fda-drugs-docs]
            [sysrev.views.components.core :as comp]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.semantic :as S :refer [Table TableHeader TableHeaderCell TableRow TableBody TableCell]]
            [sysrev.util :as util :refer [wrap-prevent-default]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:fda-drugs-docs-search]
                   :state state :get [panel-get] :set [panel-set])

(reg-sub ::page-number #(or (panel-get % :page-number) 1))

(reg-event-db ::page-number [trim-v]
              (fn [db [n]]
                (panel-set db :page-number n)))

(defn pmids-per-page [] 10)

(reg-sub ::current-query
         (fn [db]
           (let [state (get-in db [:state :panels [:fda-drugs-docs-search]])]
             (fda-drugs-docs/canonicalize-query
              {:filters (:filters state)
               :search (:current-search-term state)}))))

(reg-event-db
 :fda-drugs-docs-search-add-entity
 (fn [db [_ query entity-id]]
   (update-in db [:data :fda-drugs-docs-search query :entity-ids] conj entity-id)))

(reg-event-db
 :fda-drugs-docs-search-complete
 (fn [db [_ query]]
   (assoc-in db [:data :fda-drugs-docs-search query :complete?] true)))

(reg-event-fx
 :fda-drugs-docs-search
 (fn [{:keys [db]} [_ query]]
   (when-not (or (get-in db [:data :fda-drugs-docs-search query :complete?])
                 (= query (get-in db [:data :fda-drugs-docs-search :current-query])))
     (when-let [ws (get-in db [:data :fda-drugs-docs-search :websocket])]
       (when-not (#{js/WebSocket.CLOSED js/WebSocket.CLOSING} (.-readyState ws))
         (.close ws 1000 "complete")))
     (as-> (datapub/subscribe!
            :on-complete
            (fn []
              (dispatch [:fda-drugs-docs-search-complete query]))
            :on-data
            (fn [^js/Object data]
              (let [entity-id (-> data .-data .-searchDataset .-id)]
                (dispatch [:fda-drugs-docs-search-add-entity query entity-id])))
            :payload
            {:query (datapub/subscribe-search-dataset "id")
             :variables
             {:input (fda-drugs-docs/query->datapub-input query)}})
         $
       {:db (-> (update-in db [:data :fda-drugs-docs-search] assoc
                           :current-query query
                           :websocket $)
                (assoc-in [:data :fda-drugs-docs-search query :entity-ids] []))}))))

(reg-event-fx
 ::fetch-results
 (fn [{:keys [db]}]
   (let [state (get-in db [:state :panels [:fda-drugs-docs-search]])
         state (assoc state
                      :current-search-term (:on-change-search-term state)
                      :import-error nil
                      :page-number 1
                      :show-results? true)]
     {:db (assoc-in db [:state :panels [:fda-drugs-docs-search]] state)
      :fx [[:dispatch [:fda-drugs-docs-search
                       (fda-drugs-docs/canonicalize-query
                        {:filters (:filters state)
                         :search (:current-search-term state)})]]]})))

(def-action :project/import-fda-drugs-docs-search
  :uri (fn [] "/api/import-trials/fda-drugs-docs")
  :content (fn [project-id query entity-ids]
             {:entity-ids entity-ids
              :project-id project-id
              :query query})
  :process (fn [_ [project-id _ _] {:keys [success]}]
             (when success
               {:dispatch [:on-add-source project-id]}))
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:fda-drugs-docs/set-import-error message]}))))

(reg-sub :fda-drugs-docs/search-complete?
         (fn [db [_ search-terms]]
           (boolean
            (get-in db [:data :fda-drugs-docs-search search-terms :complete?]))))

(reg-sub :fda-drugs-docs/query-result
         (fn [db [_ query]]
           (get-in db [:data :fda-drugs-docs-search query :entity-ids])))

(reg-event-db :fda-drugs-docs/set-import-error [trim-v]
              (fn [db [message]]
                (panel-set db :import-error message)))

(defn SearchResultArticlesPager []
  (let [items-per-page (pmids-per-page)
        current-page @(subscribe [::page-number])
        query @(subscribe [::current-query])
        search-results @(subscribe [:fda-drugs-docs/query-result query])
        offset (* (dec current-page) items-per-page)]
    [:div.ui.segment
     [ListPager
      {:panel panel
       :instance-key [:fda-drugs-docs-search-results]
       :offset offset
       :total-count (count search-results)
       :items-per-page items-per-page
       :item-name-string "articles"
       :set-offset #(dispatch [::page-number (inc (quot % items-per-page))])
       :recent-nav-action nil
       :loading? nil}]]))

(defn ProductsSummary [products]
  [TableRow
   [TableCell]
   [TableCell {:colSpan 4}
    "Products:"
    [:ul
     (for [{:strs [ActiveIngredient DrugName Form MarketingStatus ProductNo Strength]} products]
       ^{:key [ProductNo]}
       [:li
        [:b DrugName]
        ", " ActiveIngredient ", " Strength ", " Form
        ", " (str/join ";" (map #(get % "Description") MarketingStatus))])]]])

(defn ArticleSummary
  "Display an article summary item"
  [entity-id]
  (let [metadata (:metadata @(subscribe [:datapub/entity* entity-id]))]
    [:<>
     [TableRow
      [TableCell [:a {:href (get metadata "ApplicationDocsURL")
                      :target "_blank"}
                  [:i.share.icon]]]
      [TableCell (get metadata "SponsorName")]
      [TableCell (get metadata "ApplicationDocsDescription")]
      [TableCell (get metadata "ApplType")]
      [TableCell (get-in metadata ["Submissions" 0 "SubmissionClass" "Description"])]]
     (when-let [products (get metadata "Products")]
       [ProductsSummary products])]))

(defn ImportArticlesButton
  "Add articles to a project from a fda-drugs-docs search"
  [& [disable-import?]]
  (let [query @(subscribe [::current-query])
        search-results @(subscribe [:fda-drugs-docs/query-result query])]
    [:div.ui.fluid.left.labeled.button.search-results
     {:on-click #(do (dispatch [:action [:project/import-fda-drugs-docs-search
                                         @(subscribe [:active-project-id])
                                         query search-results]])
                     (dispatch [(keyword 'sysrev.views.panels.project.add-articles
                                         :add-documents-visible)
                                false])
                     (reset! state {}))}
     [:div.ui.fluid.right.pointing.label
      (str "Found " (count search-results) " articles")]
     [:button.ui.blue.button
      {:class (when disable-import? "disabled")}
      [:i.download.icon] " Import"]]))

(defn CloseSearchResultsButton []
  (let [show-results? (r/cursor state [:show-results?])]
    [:button.ui.fluid.right.labeled.icon.button
     {:class "search-results close-search"
      :style {:margin-right "0"}
      :on-click #(reset! show-results? false)}
     "Close " [:i.times.icon]]))

(defn SearchBar
  "The search input for a fda-drugs-docs query"
  []
  (let [on-change-search-term (r/cursor state [:on-change-search-term])]
    [:form {:id "search-bar"
            :class "fda-drugs-docs-search"
            :on-submit (wrap-prevent-default
                        #(dispatch [::fetch-results]))
            :style {:margin-top "1em"
                    :margin-bottom "1em"}}
     [:div.ui.fluid.left.icon.action.input
      [:input {:type "text"
               :placeholder "Search Drugs@FDA Application Documents..."
               :value @on-change-search-term
               :on-change (util/on-event-value #(reset! on-change-search-term %))}]
      [:i.search.icon]
      [:button.ui.button {:type "submit" :tabIndex "-1"}
       "Search"]]]))

(defn SearchActions [& [disable-import?]]
  (let [query @(subscribe [::current-query])
        search-results @(subscribe [:fda-drugs-docs/query-result query])]
    (when (and search-results @(r/cursor state [:show-results?]))
      [:div.ui.top.attached.segment.aligned.stackable.grid
       {:style {:border-bottom-width "0"}}
       [:div.eight.wide.column.results-header
        [ImportArticlesButton disable-import?]]
       [:div.eight.wide.column.results-header.results-buttons
        [:div.ui.two.column.grid
         [:div.column [CloseSearchResultsButton]]]]])))

(defn SearchResultsView []
  (let [query @(subscribe [::current-query])
        show-results? @(r/cursor state [:show-results?])
        items-per-page (pmids-per-page)
        current-page @(subscribe [::page-number])
        search-results (->> @(subscribe [:fda-drugs-docs/query-result query])
                            (drop (* items-per-page (dec current-page))))]
    (when show-results?
      (doseq [entity-id (take (* 2 items-per-page) search-results)]
        (dispatch [:require [:datapub-entity* entity-id]]))
      [:div.fda-drugs-docs-search-results
       [:div.ui.bottom.attached.segment.fda-drugs-docs-articles
        {:style (if (seq search-results) {} {:min-height "800px"})}
        [SearchResultArticlesPager]
        (if (seq search-results)
          [:<>
           [Table {:striped true}
            [TableHeader
             [TableRow
              [TableHeaderCell "Link"]
              [TableHeaderCell "SponsorName"]
              [TableHeaderCell "Doc Description"]
              [TableHeaderCell "Application Type"]
              [TableHeaderCell "Submission Class"]]]
            [TableBody
             (for [entity-id (take items-per-page search-results)]
               ^{:key entity-id} [ArticleSummary entity-id])]]]
          [:<>
           [:div.ui.active.inverted.dimmer>div.ui.loader]
           [SearchResultArticlesPager]])]])))

(defn SearchResultsContainer []
  (let [query @(subscribe [::current-query])
        current-search-term @(r/cursor state [:current-search-term])
        import-error @(r/cursor state [:import-error])
        search-results @(subscribe [:fda-drugs-docs/query-result query])]
    (cond import-error
          [:div.ui.segment.bottom.attached.search-results-container.margin
           [:div.ui.error.message
            (str import-error)]]
          ;; search input form is empty
          (empty? current-search-term)
          nil
          ;; valid search is completed with no results
          (and (zero? (count search-results))
               @(subscribe [:fda-drugs-docs/search-complete? query]))
          [:div.ui.segment.bottom.attached.search-results-container.margin
           [:h3 "No documents match your search terms"]]
          :else [SearchResultsView])))

(defn SearchFilters []
  [:<>
   [comp/MultiSelect
    {:cursor (r/cursor state [:filters :application-type])
     :label "Application Type"
     :on-change #(dispatch [::fetch-results])
     :options fda-drugs-docs/application-type-options}]])
