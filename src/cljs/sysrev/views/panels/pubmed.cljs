(ns sysrev.views.panels.pubmed
  (:require [clojure.string :as str]
            [cljs-http.client :as http-client]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.util :refer [wrap-prevent-default nbsp]]
            [sysrev.shared.util :as util]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:pubmed-search] {:state-var state
                                           :get-fn panel-get
                                           :set-fn panel-set})

(reg-sub ::page-number #(or (panel-get % :page-number) 1))

(reg-event-db ::page-number [trim-v]
              (fn [db [n]]
                (panel-set db :page-number n)))

(defn pmids-per-page [] 20)

(def-data :pubmed-search
  :loaded? (fn [db search-term page-number]
             (let [result-count (get-in db [:data :pubmed-search search-term :count])]
               ;; the result-count hasn't been updated, so the search term results
               ;; still need to be populated
               (if (nil? result-count)
                 false
                 ;; the page number exists
                 (if (<= page-number
                         (Math/ceil (/ result-count (pmids-per-page))))
                   (not-empty (get-in db [:data :pubmed-search search-term
                                          :pages page-number :pmids]))
                   ;; the page number doesn't exist, retrieve nothing
                   true))))
  :uri (constantly "/api/pubmed/search")
  :content (fn [search-term page-number]
             {:term search-term :page-number page-number})
  :process (fn [_ [search-term page-number] response]
             {:dispatch [:pubmed/save-search-term-results search-term page-number response]})
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:pubmed/set-import-error message]}))))

(def-data :pubmed-summaries
  :loaded? (fn [db search-term page-number _]
             (let [result-count (get-in db [:data :pubmed-search search-term :count])]
               (if (<= page-number
                       (Math/ceil (/ result-count (pmids-per-page))))
                 ;; the page number exists, the results should too
                 (not-empty (get-in db [:data :pubmed-search search-term
                                        :pages page-number :summaries]))
                 ;; the page number isn't in the result, retrieve nothing
                 true)))
  :uri (constantly "/api/pubmed/summaries")
  :content (fn [_ _ pmids] {:pmids (str/join "," pmids)})
  :process (fn [_ [search-term page-number _] response]
             {:dispatch [:pubmed/save-search-term-summaries search-term page-number response]}))

(def-action :project/import-articles-from-search
  :uri (fn [] "/api/import-articles/pubmed")
  :content (fn [project-id search-term source]
             {:project-id project-id
              :search-term search-term
              :source source})
  :process (fn [_ [project-id _ _] {:keys [success]}]
             (when success
               {:dispatch-n (list [:reload [:project/sources project-id]]
                                  [:add-articles/reset-state!])}))
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:pubmed/set-import-error message]}))))

(reg-sub :pubmed/search-term-result
         (fn [db [_ search-term]]
           (-> db :data :pubmed-search (get-in [search-term]))))

;; A DB map representing a search term in :data :search-term <term>
;;
;;{:count <integer> ; total amount of documents that match a search term
;; :pages {<page-number> ; an integer
;;         {:pmids [PMIDS] ; a vector of PMID integers associated with page_no
;;          :summaries {<pmid> ; an integer, should be in [PMIDS] vector above
;;                      { PubMed Summary map} ; contains many key/val pairs
;;                     }
;;         }
;;}

(reg-event-fx
 :pubmed/save-search-term-results
 [trim-v]
 ;; WARNING: This fn must return something (preferable the db map),
 ;;          otherwise the system will hang!!!
 (fn [{:keys [db]} [search-term page-number search-term-response]]
   (let [pmids (:pmids search-term-response)
         page-inserter
         ;; We only want to insert a {page-number [pmids]} map
         ;; if there are actually pmids for that page-number
         ;; associated with the search term
         ;; e.g. if you ask /api/pubmed/search for page-number 30 of the term "foo bar"
         ;; you will get back an empty vector. This fn discards that response
         (fn [db] (update-in db [:data :pubmed-search search-term :pages]
                             #(let [data {page-number {:pmids pmids}}]
                                ;; on the first request, the :pages keyword
                                ;; doesn't yet exist so conj will return a list
                                ;; and not a map. This makes sure only maps
                                ;; are saved in our DB
                                (if (nil? %)
                                  data
                                  (conj % data)))))]
     (if-not (empty? pmids)
       {:db (-> db
                ;; include the count
                (assoc-in [:data :pubmed-search search-term :count]
                          (:count search-term-response))
                ;; include the page-number and associated pmids
                page-inserter)
        :dispatch [:require [:pubmed-summaries search-term page-number pmids]]}
       {:db (assoc-in db [:data :pubmed-search search-term :count]
                      (:count search-term-response))}))))

(reg-event-db :pubmed/save-search-term-summaries [trim-v]
              (fn [db [search-term page-number response]]
                (assoc-in db [:data :pubmed-search search-term :pages page-number :summaries]
                          response)))

(reg-event-db :pubmed/set-import-error [trim-v]
              (fn [db [message]]
                (panel-set db :import-error message)))

(defn SearchResultArticlesPager []
  (let [items-per-page (pmids-per-page)
        current-page (subscribe [::page-number])
        current-search-term (r/cursor state [:current-search-term])
        search-results
        @(subscribe [:pubmed/search-term-result @current-search-term])
        n-results (get-in search-results [:count])
        on-navigate (fn [_ _offset]
                      (dispatch [:require [:pubmed-search @current-search-term @current-page]]))
        offset (* (dec @current-page) items-per-page)]
    [:div.ui.segment
     [ListPager
      {:panel panel
       :instance-key [:pubmed-search-results]
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
  [article global-idx]
  (let [{:keys [uid title authors source pubdate volume pages elocationid]} article]
    [:div.ui.segment.pubmed-article
     [:div.content
      [:span
       (str (inc global-idx) "." nbsp nbsp)
       (if (nil? title)
         [:a (when uid {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/" uid)
                        :target "_blank"})
          "[Error]"]
         [ui/dangerous
          :a (when uid
               {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/" uid)
                :target "_blank"})
          (util/parse-html-str title)])]
      (when authors
        [:p.bold (->> authors (mapv :name) (str/join ", "))])
      [:p (str source ". " pubdate
               (when-not (empty? volume)
                 (str "; " volume ":" pages))
               ". " elocationid ".")]
      [:p.pmid
       (str "PMID: " uid)
       nbsp nbsp nbsp nbsp
       [:a (when uid
             {:href (str "https://www.ncbi.nlm.nih.gov/pubmed?"
                         "linkname=pubmed_pubmed&from_uid=" uid)
              :target "_blank"})
        "Similar articles"]]]]))

(defn ImportArticlesButton
  "Add articles to a project from a PubMed search"
  [& [disable-import?]]
  (let [current-search-term (r/cursor state [:current-search-term])
        project-id (subscribe [:active-project-id])
        search-results @(subscribe [:pubmed/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])]
    [:div.ui.fluid.left.labeled.button.search-results
     {:on-click
      #(do (dispatch [:action [:project/import-articles-from-search
                               @project-id @current-search-term "PubMed"]])
           (reset! state {}))}
     [:div.ui.fluid.right.pointing.label
      (str "Found " n-results " articles")]
     [:button.ui.blue.button
      {:class (when disable-import? "disabled")}
      [:i.download.icon] " Import"]]))

(defn PubMedSearchLink
  "Return a link to PubMed for the current search term"
  []
  (let [on-change-search-term (r/cursor state [:on-change-search-term])]
    [:a.ui.fluid.right.labeled.icon.button.search-results
     {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/?"
                 (http-client/generate-query-string
                  {:term @on-change-search-term}))
      :target "_blank"}
     "PubMed " [:i.external.icon]]))

(defn CloseSearchResultsButton []
  (let [show-results? (r/cursor state [:show-results?])]
    [:button.ui.fluid.right.labeled.icon.button
     {:class "search-results close-search"
      :on-click #(reset! show-results? false)
      :style {:margin-right "0"}}
     "Close " [:i.times.icon]]))

(defn SearchBar
  "The search input for a pubmed query"
  []
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])
        show-results? (r/cursor state [:show-results?])
        import-error (r/cursor state [:import-error])
        fetch-results
        #(do (reset! current-search-term @on-change-search-term)
             (reset! page-number 1)
             (reset! show-results? true)
             (reset! import-error nil)
             (dispatch [:require [:pubmed-search @current-search-term 1]]))]
    [:form {:on-submit (wrap-prevent-default fetch-results)
            :id "search-bar"}
     [:div.ui.fluid.left.icon.action.input
      [:input {:type "text"
               :placeholder "PubMed Search..."
               :value @on-change-search-term
               :on-change (fn [event]
                            (reset! on-change-search-term
                                    (-> event
                                        (aget "target")
                                        (aget "value"))))}]
      [:i.search.icon]
      [:button.ui.button {:type "submit" :tabIndex "-1"}
       "Search"]]]))

(defn SearchActions [& [disable-import?]]
  (let [current-search-term (r/cursor state [:current-search-term])
        search-results @(subscribe [:pubmed/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])
        show-results? (r/cursor state [:show-results?])]
    (when (and n-results @show-results?)
      [:div.ui.attached.segment.middle.aligned.stackable.grid
       [:div.eight.wide.column.results-header
        [ImportArticlesButton disable-import?]]
       [:div.eight.wide.column.results-header.results-buttons
        [:div.ui.two.column.grid
         [:div.column [PubMedSearchLink]]
         [:div.column [CloseSearchResultsButton]]]]])))

(defn SearchResultsView []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (subscribe [::page-number])
        show-results? (r/cursor state [:show-results?])
        search-results @(subscribe [:pubmed/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])
        page-offset (* (- @page-number 1) (pmids-per-page))
        have-entries?
        (not-empty (get-in search-results [:pages @page-number :summaries]))]
    (when (and n-results @show-results?)
      [:div.pubmed-search-results
       (when (and (not-empty @current-search-term)
                  @page-number)
         (dispatch [:require [:pubmed-search @current-search-term @page-number]]))
       [:div.ui.segments.pubmed-articles
        {:style (if have-entries? {}
                    {:min-height "800px"})}
        [SearchResultArticlesPager]
        (if have-entries?
          (doall
           (map-indexed
            (fn [idx pmid]
              ^{:key pmid}
              [ArticleSummary
               (get-in search-results [:pages @page-number :summaries pmid])
               (+ page-offset idx)])
            (get-in search-results [:pages @page-number :pmids])))
          [:div.ui.active.inverted.dimmer
           [:div.ui.loader]])
        (when have-entries?
          [SearchResultArticlesPager])]])))

(defn SearchResultsContainer []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (subscribe [::page-number])
        import-error (r/cursor state [:import-error])
        search-results (subscribe [:pubmed/search-term-result
                                   @current-search-term])]
    (cond @import-error
          [:div.ui.segment.search-results-container.margin
           [:div.ui.error.message
            (str @import-error)]
           [:div "Not getting results when you would expect to see them? Try importing PubMed results with a "
            [:a {:on-click (fn [e] (.preventDefault e) (dispatch [:add-articles/reset-import-tab! :pmid]))
                 :style {:cursor "pointer"}} "PMID file"]]]
          ;; search input form is empty
          (or (nil? @current-search-term)
              (empty? @current-search-term))
          nil
          ;; valid search is completed with no results
          (and (not (nil? @current-search-term))
               (= (get-in @search-results [:count]) 0)
               (not (loading/item-loading?
                     [:pubmed-search @current-search-term @page-number])))
          [:div.ui.segment.search-results-container.margin
           [:h3 "No documents match your search terms"]]
          :else [SearchResultsView])))

(defn SearchPanel
  "A panel for searching pubmed"
  []
  [:div.search-panel
   [SearchBar]
   [SearchActions]
   [SearchResultsContainer]])

(defmethod panel-content panel []
  (fn [_child] [SearchPanel]))
