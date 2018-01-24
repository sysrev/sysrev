(ns sysrev.views.panels.pubmed
  (:require
   [cljs-http.client :as http-client]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [re-frame.db :refer [app-db]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :refer [CenteredColumn]]
   [sysrev.util :refer [wrap-prevent-default]]
   [clojure.string :as str]))

(def panel [:pubmed-search])

(def initial-state {:current-search-term nil
                    :on-change-search-term nil
                    :page-number 1
                    :pmids-per-page 20})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn TextInput
  [{:keys [value default-value placeholder on-change class]
    :or {default-value ""}}]
  [:input.ui.input
   {:class class
    :type "text"
    :value @value
    :defaultValue default-value
    :placeholder placeholder
    :on-change on-change}])

(defn SearchResultArticlesPager
  "
  {:total-pages  integer ; the amount of pages
   :current-page integer ; r/atom, the current page number we are on
   :on-click     fn      ; called whenever a pager element is clicked,optional
  }"
  [{:keys [total-pages current-page on-click]}]
  (let [page-width 1
        pages (->> (range 1 (+ 1 total-pages))
                   (partition-all page-width))
        displayed-pages (->> pages
                             (filter (fn [i] (some #(= @current-page %) i)))
                             first)
        input-value (r/atom (str @current-page))]
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
                           (when on-click (on-click)))}
           [:i.angle.double.left.icon]
           "First"]
          [:div.ui.tiny.icon.button
           {:class (when (= @current-page 1)
                     "disabled")
            :on-click #(let [new-current-page (- (first displayed-pages) 1)]
                         (if (< new-current-page 1)
                           (reset! current-page 1)
                           (reset! current-page new-current-page))
                         (when on-click (on-click)))}
           [:i.angle.left.icon]
           "Previous"]]]
        [:div.six.wide.column.center.aligned
         [:form.ui.action.input.page-number
          {:on-submit
           (wrap-prevent-default
            (fn []
              (let [value (cljs.reader/read-string @input-value)]
                (cond (= value @current-page)
                      true

                      (not= (type value) (type 1))
                      (reset! input-value @current-page)

                      (not (<= 1 value total-pages))
                      (reset! input-value @current-page)

                      (<= 1 value total-pages)
                      (do (reset! current-page value)
                          (when on-click (on-click)))))))}
          [:div
           "Page "
           [TextInput {:class "search-page-number"
                       :value input-value
                       :on-change #(reset! input-value (-> %
                                                           (aget "target")
                                                           (aget "value")))}]
           (str " of " total-pages)]]]
        [:div.five.wide.column.right.aligned
         [:div.ui.tiny.buttons
          [:div.ui.tiny.icon.button
           {:class (when (= @current-page total-pages)
                     "disabled")
            :on-click #(let [new-current-page (+ (last displayed-pages) 1)]
                         (if (> new-current-page total-pages)
                           (reset! current-page total-pages)
                           (reset! current-page new-current-page))
                         (when on-click (on-click)))}
           [:i.angle.right.icon]
           "Next"]
          [:div.ui.tiny.icon.button
           {:class (when (= @current-page total-pages)
                     "disabled")
            :on-click #(do (reset! current-page total-pages)
                           (when on-click (on-click)))}
           [:i.angle.double.right.icon]
           "Last"]]]]])))

(defn ArticleSummary
  "Display an article summary item"
  [article item-idx]
  (let [{:keys [uid title authors source pubdate volume pages elocationid]} article]
    [:div.ui.segment
     item-idx [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/"  uid)
                   :target "_blank"}
               title]
     [:p.bold (->> authors (mapv :name) (str/join ", "))]
     [:p (str source ". " pubdate
              (when-not (empty? volume)
                (str "; " volume ":" pages))
              ". " elocationid ".")]
     [:p (str "PMID: " uid)]
     [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed?linkname=pubmed_pubmed&from_uid=" uid)}
      "Similar articles"]]))

(defn ImportArticlesButton
  "Add articles to a project from a PubMed search"
  []
  (let [current-search-term (r/cursor state [:current-search-term])]
    [:div.ui.tiny.icon.button.search-results
     {:on-click #(dispatch [:action [:project/import-articles-from-search
                                     @current-search-term "PubMed"]])}
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
        fetch-results
        #(do (reset! current-search-term @on-change-search-term)
             (reset! page-number 1)
             (reset! show-results? true)
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
         [:div.ui.six.wide.column
          [CenteredColumn (str "Found " n-results " articles")]]
         [:div.ui.ten.wide.right.aligned.column
          [ImportArticlesButton]
          [PubMedSearchLink]
          [CloseSearchResultsButton]]]]
       [SearchResultArticlesPager
        {:total-pages (Math/ceil (/ (get-in search-results [:count])
                                    @pmids-per-page))
         :current-page page-number
         :on-click (fn []
                     (dispatch [:require [:pubmed-search
                                          @current-search-term @page-number]]))}]
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
          [SearchResultArticlesPager
           {:total-pages (Math/ceil (/ (get-in search-results [:count])
                                       @pmids-per-page))
            :current-page page-number
            :on-click (fn []
                        (dispatch [:require [:pubmed-search
                                             @current-search-term @page-number]]))}]]
         [:div.ui.active.centered.loader.inline
          [:div.ui.loader]])])))

(defn SearchResultsContainer []
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (r/cursor state [:page-number])
        pmids-per-page (r/cursor state [:pmids-per-page])
        search-results (subscribe [:pubmed/search-term-result
                                   @current-search-term])
        result-count (get-in @search-results [:count])]
    [:div.search-results-container
     (cond
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
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])]
    [:div.search-panel
     [SearchBar]
     [SearchResultsContainer]]))

(defmethod panel-content panel []
  (fn [child]
    (ensure-state)
    [:div.ui.segment
     [SearchPanel]]))
