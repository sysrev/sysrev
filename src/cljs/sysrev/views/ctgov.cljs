(ns sysrev.views.ctgov
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.semantic :refer [Table TableHeader TableHeaderCell TableRow TableBody TableCell]]
            [sysrev.util :as util :refer [wrap-prevent-default]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:ctgov-search]
                   :state state :get [panel-get] :set [panel-set])

(reg-sub ::page-number #(or (panel-get % :page-number) 1))

(reg-event-db ::page-number [trim-v]
              (fn [db [n]]
                (panel-set db :page-number n)))

(defn pmids-per-page [] 10)

(def-data :ctgov-search
  :loaded? (fn [db search-term page-number]
             (let [result-count (get-in db [:data :ctgov-search search-term :count])]
               ;; the result-count hasn't been updated, so the search term results
               ;; still need to be populated
               (if (nil? result-count)
                 false
                 ;; the page number exists
                 (if (<= page-number
                         (Math/ceil (/ result-count (pmids-per-page))))
                   (not-empty (get-in db [:data :ctgov-search search-term
                                          :pages page-number :summaries]))
                   ;; the page number doesn't exist, retrieve nothing
                   true))))

  :uri (constantly "/api/ctgov/search")
  :content (fn [search-term page-number]
             {:term search-term :page-number page-number})
  :process (fn [{:keys [db]} [search-term page-number] {:keys [results count]}]
             {:db (-> db
                      (assoc-in [:data :ctgov-search search-term :pages page-number :summaries]
                                results)
                      (assoc-in [:data :ctgov-search search-term :count]
                                count))}))

(def-action :project/import-trials-from-search
  :uri (fn [] "/api/import-trials/ctgov")
  :content (fn [project-id search-term]
             {:search-term search-term :project-id project-id})
  :process (fn [_ [project-id _ _] {:keys [success]}]
             (when success
               {:dispatch [:reload [:project/sources project-id]]}))
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:ctgov/set-import-error message]}))))

(reg-sub :ctgov/search-term-result
         (fn [db [_ search-term]]
           (-> db :data :ctgov-search (get search-term))))

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
        n-results (get-in search-results [:count])
        on-navigate (fn [_ _offset]
                      (dispatch [:require [:ctgov-search @current-search-term @current-page]]))
        offset (* (dec @current-page) items-per-page)]
    [:div.ui.segment
     [ListPager
      {:panel panel
       :instance-key [:ctgov-search-results]
       :offset offset
       :total-count n-results
       :items-per-page items-per-page
       :item-name-string "articles"
       :set-offset #(dispatch [::page-number (inc (quot % items-per-page))])
       :on-nav-action on-navigate
       :recent-nav-action nil
       :loading? nil}]]))

(defn ArticleSummary
  "Display an article summary item"
  [article]
  (let [{:keys [status study-title conditions interventions locations]} article]
    [TableRow
     [TableCell {:style {:color (cond (or (= status "Recruiting")
                                          (= status "Not yet recruiting"))
                                      "green"
                                      (or (= status "Unknown")
                                          (= status "Unknown status"))
                                      "#f5c88a"
                                      :else
                                      "red")}} status]
     [TableCell study-title]
     [TableCell (clojure.string/join "," conditions)]
     [TableCell (let [amt (count (:type interventions))]
                  [:ul (map (fn [x y] ^{:key (gensym)}
                              [:li (str x ": " y)])
                            (take 3 (:type interventions))
                            (take 3 (:name interventions)))
                   (when (> amt 3)
                     [:li (str "(and " (- amt 3) " more...)")])])]
     [TableCell (let [locs (nth locations 0)
                      locs-count (count locs)]
                  [:ul
                   (map-indexed
                    (fn [idx facility]
                      (let [city (get (nth locations 1) idx)
                            state (get (nth locations 2) idx)
                            country (get (nth locations 3) idx)]
                        ^{:key (gensym)}
                        [:li (str facility ", " city ", "
                                  (when (= country "United States") (str state ", "))
                                  country)]))
                    (take 3 locs))
                   (when (> locs-count 3)
                     [:li (str "(and " (- locs-count 3)  " more..)")])])]]))

(defn ImportArticlesButton
  "Add articles to a project from a ctgov search"
  [& [disable-import?]]
  (let [current-search-term (r/cursor state [:current-search-term])
        project-id (subscribe [:active-project-id])
        search-results @(subscribe [:ctgov/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])]
    [:div.ui.fluid.left.labeled.button.search-results
     {:on-click #(do (dispatch [:action [:project/import-trials-from-search
                                         @project-id @current-search-term]])
                     (reset! state {}))}
     [:div.ui.fluid.right.pointing.label
      (str "Found " n-results " articles")]
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
                           (dispatch [:require [:ctgov-search @current-search-term 1]]))]
    [:form {:id "search-bar"
            :class "ctgov-search"
            :on-submit (wrap-prevent-default fetch-results)}
     [:div.ui.fluid.left.icon.action.input
      [:input {:type "text"
               :placeholder "Search ClinicalTrials.gov..."
               :value @on-change-search-term
               :on-change (util/on-event-value #(reset! on-change-search-term %))}]
      [:i.search.icon]
      [:button.ui.button {:type "submit" :tabIndex "-1"}
       "Search"]]]))

(defn SearchActions [& [disable-import?]]
  (let [current-search-term (r/cursor state [:current-search-term])
        search-results @(subscribe [:ctgov/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])
        show-results? (r/cursor state [:show-results?])]
    (when (and n-results @show-results?)
      [:div.ui.attached.segment.middle.aligned.stackable.grid
       {:style {:border-bottom-width "0"}}
       [:div.eight.wide.column.results-header
        [ImportArticlesButton disable-import?]]
       [:div.eight.wide.column.results-header.results-buttons
        [:div.ui.two.column.grid
         [:div.column [CloseSearchResultsButton]]]]])))

(defn SearchResultsView []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (subscribe [::page-number])
        show-results? (r/cursor state [:show-results?])
        search-results @(subscribe [:ctgov/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])
        have-entries?
        (not-empty (get-in search-results [:pages @page-number :summaries]))]
    (when (and n-results @show-results?)
      [:div.ctgov-search-results
       (when (and @page-number (not-empty @current-search-term))
         (dispatch [:require [:ctgov-search @current-search-term @page-number]]))
       [:div.ui.bottom.attached.segment.ctgov-articles
        {:style (if have-entries? {} {:min-height "800px"})}
        [SearchResultArticlesPager]
        (if have-entries?
          (doall
           [Table {:striped true}
            [TableHeader
             [TableRow
              [TableHeaderCell "Status"]
              [TableHeaderCell "Study Title"]
              [TableHeaderCell "Conditions"]
              [TableHeaderCell "Interventions"]
              [TableHeaderCell "Locations"]]]
            [TableBody
             (doall (for [{:keys [nctid] :as doc}
                          (get-in search-results [:pages @page-number :summaries])]
                      ^{:key nctid} [ArticleSummary doc]))]])
          [:div.ui.active.inverted.dimmer>div.ui.loader])
        (when have-entries?
          [SearchResultArticlesPager])]])))

(defn SearchResultsContainer []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (subscribe [::page-number])
        import-error (r/cursor state [:import-error])
        search-results (subscribe [:ctgov/search-term-result
                                   @current-search-term])]
    (cond @import-error
          [:div.ui.segment.bottom.attached.search-results-container.margin
           [:div.ui.error.message
            (str @import-error)]]
          ;; search input form is empty
          (or (nil? @current-search-term)
              (empty? @current-search-term))
          nil
          ;; valid search is completed with no results
          (and (not (nil? @current-search-term))
               (= (get-in @search-results [:count]) 0)
               (not (data/loading?
                     [:ctgov-search @current-search-term @page-number])))
          [:div.ui.segment.bottom.attached.search-results-container.margin
           [:h3 "No documents match your search terms"]]
          :else [SearchResultsView])))
