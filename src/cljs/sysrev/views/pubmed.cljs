(ns sysrev.views.pubmed
  (:require [clojure.string :as str]
            [cljs-http.client :as http]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.util :as util :refer [wrap-prevent-default nbsp]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:pubmed-search]
                   :state state :get [panel-get] :set [panel-set])

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
  :uri     "/api/import-articles/pubmed"
  :content (fn [project-id search-term source]
             {:project-id project-id
              :search-term search-term
              :source source})
  :process (fn [_ [project-id _ _] {:keys [success]}]
             (when success
               {:dispatch [:on-add-source project-id]}))
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:pubmed/set-import-error message]}))))

(reg-sub :pubmed/search-term-result
         (fn [db [_ search-term]]
           (get-in db [:data :pubmed-search search-term])))

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

(reg-event-fx :pubmed/save-search-term-results [trim-v]
              (fn [{:keys [db]} [search-term page-number search-term-response]]
                (let [{:keys [pmids count]} search-term-response]
                  (if (seq pmids)
                    {:db (-> (assoc-in db [:data :pubmed-search search-term :count] count)
                             (assoc-in    [:data :pubmed-search search-term :pages page-number]
                                          {:pmids pmids}))
                     :dispatch [:require [:pubmed-summaries search-term page-number pmids]]}
                    {:db (assoc-in db [:data :pubmed-search search-term :count] count)}))))

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
        search-term (r/cursor state [:current-search-term])]
    [:div.ui.segment
     [ListPager
      {:panel panel
       :instance-key [:pubmed-search-results]
       :offset (* (dec @current-page) items-per-page)
       :total-count (:count @(subscribe [:pubmed/search-term-result @search-term]))
       :items-per-page items-per-page
       :item-name-string "articles"
       :set-offset #(dispatch [::page-number (inc (quot % items-per-page))])
       :on-nav-action (fn [& _] (dispatch [:require [:pubmed-search @search-term @current-page]]))
       :recent-nav-action nil
       :loading? nil}]]))

(defn ArticleSummary [article global-idx]
  (let [{:keys [uid title authors source pubdate volume pages elocationid]} article]
    [:div.ui.segment.pubmed-article>div.content
     [:span (str (inc global-idx) "." nbsp nbsp)
      [ui/dangerous
       :a (when uid {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/" uid)
                     :target "_blank"})
       (-> (some-> title util/parse-html-str)
           (or "[Error]"))]]
     (when authors
       [:p.bold (->> authors (map :name) (str/join ", "))])
     [:p (str source ". " pubdate
              (when (seq volume) (str "; " volume ":" pages))
              ". " elocationid ".")]
     [:p.pmid (str "PMID: " uid) nbsp nbsp nbsp nbsp
      [:a (when uid {:href (str "https://www.ncbi.nlm.nih.gov/pubmed?"
                                "linkname=pubmed_pubmed&from_uid=" uid)
                     :target "_blank"})
       "Similar articles"]]]))

(defn ImportArticlesButton
  "Add articles to a project from a PubMed search"
  [& [disable-import?]]
  (let [current-search-term (r/cursor state [:current-search-term])
        project-id (subscribe [:active-project-id])
        search-results @(subscribe [:pubmed/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])]
    [:div.ui.fluid.left.labeled.button.search-results
     {:on-click #(do (run-action :project/import-articles-from-search
                                 @project-id @current-search-term "PubMed")
                     (dispatch [(keyword 'sysrev.views.panels.project.add-articles
                                         :add-documents-visible)
                                false])
                     (reset! state {}))}
     [:div.ui.fluid.right.pointing.label
      (str "Found " n-results " articles")]
     [:button.ui.blue.button
      {:class (when disable-import? "disabled")
       :id :import-articles-pubmed}
      [:i.download.icon] " Import"]]))

(defn PubMedSearchLink
  "Return a link to PubMed for the current search term"
  []
  (let [on-change-search-term (r/cursor state [:on-change-search-term])]
    [:a.ui.fluid.right.labeled.icon.button.search-results
     {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/?"
                 (http/generate-query-string {:term @on-change-search-term}))
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
            :id "search-bar"
            :style {:margin-top "1em"
                    :margin-bottom "1em"}}
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
      [:div.ui.top.attached.segment.aligned.stackable.grid
       {:style {:border-bottom-width "0"}}
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
       [:div.ui.bottom.attached.segment.pubmed-articles
        {:style (when-not have-entries? {:min-height "800px"})}
        [SearchResultArticlesPager]
        (if have-entries?
          (doall (map-indexed (fn [idx pmid] ^{:key pmid}
                                [ArticleSummary
                                 (get-in search-results [:pages @page-number :summaries pmid])
                                 (+ page-offset idx)])
                              (get-in search-results [:pages @page-number :pmids])))
          [:div.ui.active.inverted.dimmer>div.ui.loader])
        (when have-entries?
          [SearchResultArticlesPager])]])))

(defn SearchResultsContainer []
  (let [{:keys [current-search-term import-error]} @state
        page-number (subscribe [::page-number])
        search-results @(subscribe [:pubmed/search-term-result current-search-term])]
    (cond import-error
          [:div.ui.bottom.attached.segment.search-results-container.margin
           [:div.ui.error.message (str import-error)]
           [:div
            "Not getting results when you would expect to see them? "
            "Try importing PubMed results with a "
            [:a {:href "#" :on-click (util/wrap-user-event
                                      #(dispatch [:add-articles/import-tab :pmid])
                                      :prevent-default true)}
             "PMID file"]]]
          ;; search input form is empty
          (empty? current-search-term)
          nil
          ;; valid search is completed with no results
          (and (= (get-in search-results [:count]) 0)
               (not (data/loading?
                     [:pubmed-search current-search-term @page-number])))
          [:div.ui.bottom.attached.segment.search-results-container.margin
           [:h3 "No documents match your search terms"]]
          :else [SearchResultsView])))
