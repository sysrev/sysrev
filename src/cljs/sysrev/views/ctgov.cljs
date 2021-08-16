(ns sysrev.views.ctgov
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.datapub :as datapub]
            [sysrev.macros :refer-macros [setup-panel-state]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.semantic :refer [Table TableHeader TableHeaderCell TableRow TableBody TableCell]]
            [sysrev.util :as util :refer [wrap-prevent-default]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:ctgov-search]
                   :state state :get [panel-get] :set [panel-set])

(reg-sub ::page-number #(or (panel-get % :page-number) 1))

(reg-event-db ::page-number [trim-v]
              (fn [db [n]]
                (panel-set db :page-number n)))

(defn pmids-per-page [] 10)

(reg-event-fx
 :ctgov-search-add-entity
 (fn [{:keys [db]} [_ search-terms entity-id]]
   {:db (update-in db [:data :ctgov-search search-terms :entity-ids] conj entity-id)
    :fx [[:dispatch [:require [:datapub-entity entity-id]]]]}))

(reg-event-db
 :ctgov-search-complete
 (fn [db [_ search-terms]]
   (assoc-in db [:data :ctgov-search search-terms :complete?] true)))

(reg-event-fx
 :ctgov-search
 (fn [{:keys [db]} [_ search-terms]]
   (when-not (or (get-in db [:data :ctgov-search search-terms :complete?])
                 (= search-terms (get-in db [:data :ctgov-search :current-search])))
     (when-let [ws (get-in db [:data :ctgov-search :websocket])]
       (when-not (#{js/WebSocket.CLOSED js/WebSocket.CLOSING} (.-readyState ws))
         (.close ws 1000 "complete")))
     (as-> (datapub/subscribe!
            :on-complete
            (fn []
              (dispatch [:ctgov-search-complete search-terms]))
            :on-data
            (fn [^js/Object data]
              (let [entity-id (-> data .-data .-searchDataset .-id)]
                (dispatch [:ctgov-search-add-entity search-terms entity-id])))
            :payload
            {:query (datapub/subscribe-search-dataset "id")
             :variables
             {:input
              {:datasetId 1
               :uniqueExternalIds true
               :query
               {:type "AND"
                :text [{:search search-terms
                        :useEveryIndex true}]}}}})
         $
       {:db (-> (update-in db [:data :ctgov-search] assoc
                           :current-search search-terms
                           :websocket $)
                (assoc-in [:data :ctgov-search search-terms :entity-ids] []))}))))

(def-action :project/import-trials-from-search
  :uri (fn [] "/api/import-trials/ctgov")
  :content (fn [project-id search-term]
             {:search-term search-term :project-id project-id})
  :process (fn [_ [project-id _ _] {:keys [success]}]
             (when success
               {:dispatch [:on-add-source project-id]}))
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:ctgov/set-import-error message]}))))

(reg-sub :ctgov/search-complete?
         (fn [db [_ search-terms]]
           (boolean
            (get-in db [:data :ctgov-search search-terms :complete?]))))

(reg-sub :ctgov/search-term-result
         (fn [db [_ search-terms]]
           (get-in db [:data :ctgov-search search-terms :entity-ids])))

;; A DB map representing a search term in :data :search-term <term>
;;
;;{:count <integer> ; total amount of documents that match a search term
;; :pages {<page-number> ; an integer
;;         {:pmids [PMIDS] ; a vector of PMID integers associated with page_no
;;          :summaries {<pmid> ; an integer, should be in [PMIDS] vector above
;;                      { Ctgov Summary map} ; contains many key/val pairs
;;                     }
;;         }
;;}

(reg-event-db :ctgov/save-search-term-summaries [trim-v]
              (fn [db [search-term page-number response]]
                (assoc-in db [:data :ctgov-search search-term :pages page-number :summaries]
                          response)))

(reg-event-db :ctgov/set-import-error [trim-v]
              (fn [db [message]]
                (panel-set db :import-error message)))

(defn SearchResultArticlesPager []
  (let [items-per-page (pmids-per-page)
        current-page (subscribe [::page-number])
        current-search-term (r/cursor state [:current-search-term])
        search-results @(subscribe [:ctgov/search-term-result @current-search-term])
        on-navigate (fn [_ _offset]
                      (dispatch [:ctgov-search @current-search-term @current-page]))
        offset (* (dec @current-page) items-per-page)]
    [:div.ui.segment
     [ListPager
      {:panel panel
       :instance-key [:ctgov-search-results]
       :offset offset
       :total-count (count search-results)
       :items-per-page items-per-page
       :item-name-string "articles"
       :set-offset #(dispatch [::page-number (inc (quot % items-per-page))])
       :on-nav-action on-navigate
       :recent-nav-action nil
       :loading? nil}]]))

(defn ArticleSummary
  "Display an article summary item"
  [entity-id]
  (let [entity @(subscribe [:datapub/entity entity-id])
        protocol (get-in entity [:content "ProtocolSection"])
        status (get-in protocol ["StatusModule" "OverallStatus"])
        interventions (get-in protocol ["ArmsInterventionsModule" "InterventionList" "Intervention"])
        locations (get-in protocol ["ContactsLocationsModule" "LocationList" "Location"])]
    (when (nil? entity) (dispatch [:require [:datapub-entity entity-id]]))
    [TableRow
     [TableCell {:style {:color (cond (or (= status "Recruiting")
                                          (= status "Not yet recruiting"))
                                      "green"
                                      (or (= status "Unknown")
                                          (= status "Unknown status"))
                                      "#f5c88a"
                                      :else
                                      "red")}} status]
     [TableCell (get-in protocol ["IdentificationModule" "OfficialTitle"])]
     [TableCell (str/join "," (get-in protocol ["ConditionsModule" "ConditionList" "Condition"]))]
     [TableCell (let [amt (count interventions)]
                  [:ul (map (fn [{:strs [InterventionName InterventionType]}]
                              ^{:key (gensym)} [:li (str InterventionType ": " InterventionName)])
                            (take 3 interventions))
                   (when (> amt 3)
                     [:li (str "(and " (- amt 3) " more...)")])])]
     [TableCell
      [:ul
       (for [{:strs [LocationCity LocationCountry LocationFacility LocationState]} (take 3 locations)]
          ^{:key (gensym)}
          [:li (str LocationFacility ", " LocationCity ", "
                    (when (= LocationCountry "United States") (str LocationState ", "))
                    LocationCountry)])
       (when (> (count locations) 3)
         [:li (str "(and " (- (count locations) 3)  " more..)")])]]]))

(defn ImportArticlesButton
  "Add articles to a project from a ctgov search"
  [& [disable-import?]]
  (let [current-search-term (r/cursor state [:current-search-term])
        project-id (subscribe [:active-project-id])
        search-results @(subscribe [:ctgov/search-term-result
                                    @current-search-term])]
    [:div.ui.fluid.left.labeled.button.search-results
     {:on-click #(do (dispatch [:action [:project/import-trials-from-search
                                         @project-id @current-search-term]])
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
  "The search input for a ctgov query"
  []
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])
        show-results? (r/cursor state [:show-results?])
        import-error (r/cursor state [:import-error])
        fetch-results #(do (reset! current-search-term @on-change-search-term)
                           (reset! page-number 1)
                           (reset! show-results? true)
                           (reset! import-error nil)
                           (dispatch [:ctgov-search @current-search-term 1]))]
    [:form {:id "search-bar"
            :class "ctgov-search"
            :on-submit (wrap-prevent-default fetch-results)
            :style {:margin-top "1em"
                    :margin-bottom "1em"}}
     [:div.ui.fluid.left.icon.action.input
      [:input {:type "text"
               :placeholder "Search ClinicalTrials.gov..."
               :value @on-change-search-term
               :on-change (util/on-event-value #(reset! on-change-search-term %))}]
      [:i.search.icon]
      [:button.ui.button {:type "submit" :tabIndex "-1"}
       "Search"]]]))

(defn SearchActions [& [disable-import?]]
  (let [current-search-term @(r/cursor state [:current-search-term])
        search-results @(subscribe [:ctgov/search-term-result current-search-term])]
    (when (and search-results @(r/cursor state [:show-results?]))
      [:div.ui.top.attached.segment.aligned.stackable.grid
       {:style {:border-bottom-width "0"}}
       [:div.eight.wide.column.results-header
        [ImportArticlesButton disable-import?]]
       [:div.eight.wide.column.results-header.results-buttons
        [:div.ui.two.column.grid
         [:div.column [CloseSearchResultsButton]]]]])))

(defn SearchResultsView []
  (let [current-search-term @(r/cursor state [:current-search-term])
        show-results? @(r/cursor state [:show-results?])
        search-results @(subscribe [:ctgov/search-term-result current-search-term])]
    (when show-results?
      [:div.ctgov-search-results
       [:div.ui.bottom.attached.segment.ctgov-articles
        {:style (if (seq search-results) {} {:min-height "800px"})}
        [SearchResultArticlesPager]
        (if (seq search-results)
          [:<>
           [Table {:striped true}
            [TableHeader
             [TableRow
              [TableHeaderCell "Status"]
              [TableHeaderCell "Study Title"]
              [TableHeaderCell "Conditions"]
              [TableHeaderCell "Interventions"]
              [TableHeaderCell "Locations"]]]
            [TableBody
             (for [entity-id search-results]
               ^{:key entity-id} [ArticleSummary entity-id])]]]
          [:<>
           [:div.ui.active.inverted.dimmer>div.ui.loader]
           [SearchResultArticlesPager]])]])))

(defn SearchResultsContainer []
  (let [current-search-term @(r/cursor state [:current-search-term])
        import-error @(r/cursor state [:import-error])
        search-results @(subscribe [:ctgov/search-term-result current-search-term])]
    (cond import-error
          [:div.ui.segment.bottom.attached.search-results-container.margin
           [:div.ui.error.message
            (str import-error)]]
          ;; search input form is empty
          (empty? current-search-term)
          nil
          ;; valid search is completed with no results
          (and (zero? (count search-results))
               @(subscribe [:ctgov/search-complete? current-search-term]))
          [:div.ui.segment.bottom.attached.search-results-container.margin
           [:h3 "No documents match your search terms"]]
          :else [SearchResultsView])))
