(ns sysrev.views.panels.pubmed
  (:require [clojure.string :as str]
            [cljs-http.client :as http-client]
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components :as ui]
            [sysrev.util :refer [wrap-prevent-default]]
            [sysrev.shared.util :as util]))

(def panel [:pubmed-search])

(def initial-state {:current-search-term nil
                    :on-change-search-term nil
                    :page-number 1
                    :pmids-per-page 20})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(def-data :pubmed-search
  :loaded?
  ;; if loaded? is false, then data will be fetched from server,
  ;; otherwise, no data is fetched. It is a fn of the dereferenced
  ;; re-frame.db/app-db.
  (fn [db search-term page-number]
    (let [pmids-per-page 20
          result-count (get-in db [:data :pubmed-search search-term :count])]
      ;; the result-count hasn't been updated, so the search term results
      ;; still need to be populated
      (if (nil? result-count)
        false
        ;; the page number exists
        (if (<= page-number
                (Math/ceil (/ result-count pmids-per-page)))
          (not-empty (get-in db [:data :pubmed-search search-term
                                 :pages page-number :pmids]))
          ;; the page number doesn't exist, retrieve nothing
          true))))

  :uri
  ;; uri is a function that returns a uri string
  (fn [] "/api/pubmed/search")

  :prereqs
  ;; a fn that returns a vector of def-data entries
  (fn [] [[:identity]])

  :content
  ;; a fn that returns a map of http parameters (in a GET context)
  ;; the parameters passed to this function are the same like in
  ;; the dispatch statement which executes the query
  ;; e.g. (dispatch [:fetch [:pubmed-query "animals" 1]])
  ;;
  ;; The data can later be retrieved using a re-frame.core/subscribe call
  ;; that is defined in sysrev.state.pubmed
  ;; e.g. @(subscribe [:pubmed/search-term-result "animals"])
  (fn [search-term page-number] {:term search-term
                                 :page-number page-number})

  :process
  ;;  fn of the form: [re-frame-db query-parameters (:result response)]
  (fn [_ [search-term page-number] response]
    {:dispatch-n
     ;; this is defined in sysrev.state.pubmed
     (list [:pubmed/save-search-term-results
            search-term page-number response])}))

(def-data :pubmed-summaries
  :loaded?
  (fn [db search-term page-number pmids]
    (let [pmids-per-page 20
          result-count (get-in db [:data :pubmed-search search-term :count])]
      (if (<= page-number
              (Math/ceil (/ result-count pmids-per-page)))
        ;; the page number exists, the results should too
        (not-empty (get-in db [:data :pubmed-search search-term
                               :pages page-number :summaries]))
        ;; the page number isn't in the result, retrieve nothing
        true)))

  :uri
  (fn [] "/api/pubmed/summaries")

  :prereqs
  (fn [] [[:identity]])

  :content
  (fn [search-term page-number pmids]
    {:pmids (clojure.string/join "," pmids)})

  :process
  (fn [_ [search-term page-number pmids] response]
    {:dispatch-n
     (list [:pubmed/save-search-term-summaries
            search-term page-number response])}))

(def-action :project/import-articles-from-search
  :uri (fn [] "/api/import-articles-from-search")
  :content (fn [project-id search-term source]
             {:project-id project-id
              :search-term search-term
              :source source})
  :process (fn [_ [project-id _ _] {:keys [success] :as result}]
             (if success
               {:dispatch-n
                (list [:reload [:project/sources project-id]]
                      [:add-articles/reset-state!])}
               ;; TODO: handle non-success?
               {}))
  :on-error (fn [{:keys [db error]} _]
              (let [{:keys [message]} error]
                (when (string? message)
                  {:dispatch [:pubmed/set-import-error message]}))))

(reg-sub
 :pubmed/search-term-result
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


(reg-event-db
 :pubmed/save-search-term-summaries
 [trim-v]
 (fn [db [search-term page-number response]]
   (assoc-in db [:data :pubmed-search search-term :pages page-number :summaries]
             response)))

(reg-event-fx
 :pubmed/set-import-error
 [trim-v]
 (fn [_ [message]]
   (swap! state assoc :import-error message)
   {}))

(defn SearchResultArticlesPager []
  (let [current-page-input (r/atom nil)]
    (fn []
      (let [current-page (r/cursor state [:page-number])
            current-search-term (r/cursor state [:current-search-term])
            search-results
            @(subscribe [:pubmed/search-term-result @current-search-term])
            n-results (get-in search-results [:count])
            pmids-per-page (r/cursor state [:pmids-per-page])
            total-pages (Math/ceil (/ n-results @pmids-per-page))
            on-navigate
            #(do (reset! current-page-input nil)
                 (dispatch [:require [:pubmed-search @current-search-term @current-page]]))
            page-width 1
            pages (->> (range 1 (+ 1 total-pages))
                       (partition-all page-width))
            displayed-pages (->> pages
                                 (filter (fn [i] (some #(= @current-page %) i)))
                                 first)]
        ;; prevent overshooting of current-page for tables
        ;; of different sizes
        (when (> @current-page displayed-pages)
          (reset! current-page 1))
        (when (> total-pages 1)
          [:div.articles-pager
           [:div.ui.grid>div.row
            [:div.five.wide.column.left.aligned
             [:div.ui.tiny.buttons
              [:div.ui.tiny.icon.button
               {:class (when (= @current-page 1)
                         "disabled")
                :on-click #(do (reset! current-page 1)
                               (when on-navigate (on-navigate)))}
               [:i.angle.double.left.icon]
               "First"]
              [:div.ui.tiny.icon.button
               {:class (when (= @current-page 1)
                         "disabled")
                :on-click #(let [new-current-page (- (first displayed-pages) 1)]
                             (if (< new-current-page 1)
                               (reset! current-page 1)
                               (reset! current-page new-current-page))
                             (when on-navigate (on-navigate)))}
               [:i.angle.left.icon]
               "Previous"]]]
            [:div.six.wide.column.center.aligned
             [:form.ui.action.input.page-number
              {:on-submit
               (wrap-prevent-default
                (fn []
                  (let [value (util/parse-integer @current-page-input)]
                    (cond (not= (type value) (type 1))
                          (reset! current-page-input nil)

                          (not (<= 1 value total-pages))
                          (reset! current-page-input nil)

                          (<= 1 value total-pages)
                          (do (reset! current-page value)
                              (reset! current-page-input nil)
                              (when on-navigate (on-navigate)))))))}
              [:div
               [:span "Page "]
               [:input.ui.input.search-page-number
                {:type "text"
                 :value (or @current-page-input (str @current-page))
                 :defaultValue ""
                 :on-change #(reset! current-page-input (-> % .-target .-value))}]
               [:span (str " of " total-pages)]]]]
            [:div.five.wide.column.right.aligned
             [:div.ui.tiny.buttons
              [:div.ui.tiny.icon.button
               {:class (when (= @current-page total-pages)
                         "disabled")
                :on-click #(let [new-current-page (+ (last displayed-pages) 1)]
                             (if (> new-current-page total-pages)
                               (reset! current-page total-pages)
                               (reset! current-page new-current-page))
                             (when on-navigate (on-navigate)))}
               [:i.angle.right.icon]
               "Next"]
              [:div.ui.tiny.icon.button
               {:class (when (= @current-page total-pages)
                         "disabled")
                :on-click #(do (reset! current-page total-pages)
                               (when on-navigate (on-navigate)))}
               [:i.angle.double.right.icon]
               "Last"]]]]])))))

(defn ArticleSummary
  "Display an article summary item"
  [article item-idx]
  (let [{:keys [uid title authors source pubdate volume pages elocationid]} article]
    [:div.ui.segment
     item-idx [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/" uid)
                   :target "_blank"}
               title]
     [:p.bold (->> authors (mapv :name) (str/join ", "))]
     [:p (str source ". " pubdate
              (when-not (empty? volume)
                (str "; " volume ":" pages))
              ". " elocationid ".")]
     [:p (str "PMID: " uid)]
     [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed?"
                     "linkname=pubmed_pubmed&from_uid=" uid)
          :target "_blank"}
      "Similar articles"]]))

(defn ImportArticlesButton
  "Add articles to a project from a PubMed search"
  []
  (let [current-search-term (r/cursor state [:current-search-term])
        project-id (subscribe [:active-project-id])]
    [:div.ui.tiny.icon.button.search-results
     {:on-click
      #(do (dispatch [:action [:project/import-articles-from-search
                               @project-id @current-search-term "PubMed"]])
           (reset! state initial-state))}
     "Import " [:i.download.icon]]))

(defn PubMedSearchLink
  "Return a link to PubMed for the current search term"
  []
  (let [on-change-search-term (r/cursor state [:on-change-search-term])]
    [:a.ui.tiny.icon.button.search-results
     {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/?"
                 (http-client/generate-query-string
                  {:term @on-change-search-term}))
      :target "_blank"}
     "PubMed " [:i.external.icon]]))

(defn CloseSearchResultsButton []
  (let [show-results? (r/cursor state [:show-results?])]
    [:div.ui.tiny.icon.button.search.results
     {:on-click #(reset! show-results? false)}
     "Close " [:i.remove.icon]]))

(defn SearchItemsCount
  "Display the total amount of items for a search term as well as the current range being viewed"
  [count page-number pmids-per-page]
  [:div.items-count
   [:h5#items-count
    [:span (str "Showing items "
                (min count (+ 1 (* (- page-number 1) pmids-per-page)))
                " to "
                (min count (* page-number pmids-per-page))
                " of " count)]]])

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
     [:div.ui.fluid.icon.input
      [:input {:type "text"
               :placeholder "PubMed Search..."
               :value @on-change-search-term
               :on-change (fn [event]
                            (reset! on-change-search-term
                                    (-> event
                                        (aget "target")
                                        (aget "value"))))}]
      [:i.inverted.circular.search.link.icon
       {:on-click fetch-results}]]]))

(defn SearchResultsView []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (r/cursor state [:page-number])
        pmids-per-page (r/cursor state [:pmids-per-page])
        show-results? (r/cursor state [:show-results?])
        search-results @(subscribe [:pubmed/search-term-result
                                    @current-search-term])
        n-results (get-in search-results [:count])]
    (when (and n-results @show-results?)
      [:div.pubmed-search-results
       [:h4.ui.dividing.header
        [:div.ui.middle.aligned.grid>div.row
         [ui/CenteredColumn
          (str "Found " n-results " articles")
          "six wide column"]
         [:div.ui.ten.wide.right.aligned.column
          [ImportArticlesButton]
          [PubMedSearchLink]
          [CloseSearchResultsButton]]]]
       [SearchResultArticlesPager]
       [SearchItemsCount
        (:count search-results)
        @page-number
        @pmids-per-page]
       (if-not (empty? (get-in search-results [:pages @page-number :summaries]))
         [:div
          (doall
           (map-indexed
            (fn [idx pmid]
              ^{:key pmid}
              [:div
               [ArticleSummary (get-in search-results
                                       [:pages @page-number :summaries pmid])
                (str (+ (+ idx 1) (* (- @page-number 1) @pmids-per-page)) ". ")]
               [:br]])
            (get-in search-results [:pages @page-number :pmids])))
          [SearchResultArticlesPager]]
         [:div.ui.active.centered.loader.inline
          [:div.ui.loader]])])))

(defn SearchResultsContainer []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (r/cursor state [:page-number])
        pmids-per-page (r/cursor state [:pmids-per-page])
        import-error (r/cursor state [:import-error])
        search-results (subscribe [:pubmed/search-term-result
                                   @current-search-term])
        result-count (get-in @search-results [:count])]
    [:div.search-results-container
     (cond
       @import-error
       [:div.ui.error.message
        (str @import-error)]

       ;; search input form is empty
       (or (nil? @current-search-term)
           (empty? @current-search-term))
       nil

       ;; valid search is completed with no results
       (and (not (nil? @current-search-term))
            (= (get-in @search-results [:count]) 0)
            (not @(subscribe [:loading? [:pubmed-search
                                         @current-search-term @page-number]])))
       [:div>h3 "No documents match your search terms"]

       :else
       [SearchResultsView])]))

(defn SearchPanel
  "A panel for searching pubmed"
  []
  (ensure-state)
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])]
    [:div.search-panel
     [SearchBar]
     [SearchResultsContainer]]))

(defmethod panel-content panel []
  (fn [child]
    [:div.ui.segment
     [SearchPanel]]))
