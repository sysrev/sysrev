(ns sysrev.views.panels.pubmed
  (:require
   [cljs-http.client :as http-client]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [sysrev.views.base :refer [panel-content]]))

(def state (r/atom {:current-search-term nil
                    :on-change-search-term nil
                    :page-number 1
                    :pmids-per-page 20}))
(defn TextInput
  "props is:
  {
  :value          ; str
  :default-value  ; str
  :placeholder    ; str, optional
  :on-change      ; fn, fn to execute on change
  }
  "
  [props]
  (fn [{:keys [value default-value placeholder on-change]
        :or {default-value ""}} props]
    [:div {:class "ui mini input"}
     [:input {:style {:width "5em"}
              :type "text"
              :value @value
              :defaultValue default-value
              :placeholder placeholder
              :on-change on-change}]]))

(defn SearchResultArticlesPager
  "props is:
  {:total-pages  integer ; the amount of pages
   :current-page integer ; r/atom, the current page number we are on
   :on-click     fn      ; called whenever a pager element is clicked,optional
  }"
  [props]
  (fn [{:keys [total-pages current-page on-click]} props]
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
        [:nav
         [:div
          [:div {:class "ui buttons"}
           [:div {:class (if (= @current-page 1)
                           "ui tiny icon button disabled"
                           "ui tiny icon button")
                  :on-click (fn [e]
                              (.preventDefault e)
                              (reset! current-page 1)
                              (when on-click (on-click)))}
            [:i {:class "angle double left icon"}]
            "First"]
           [:div {:class (if (= @current-page 1)
                           "ui tiny icon button disabled"
                           "ui tiny icon button")
                  :on-click
                  (fn [e]
                    (.preventDefault e)
                    (let [new-current-page (- (first displayed-pages) 1)]
                      (if (< new-current-page 1)
                        (reset! current-page 1)
                        (reset! current-page new-current-page)))
                    (when on-click (on-click)))}
            [:i {:class "angle left icon"}]
            "Previous"]]
          [:form {:class "ui action input"
                  :style {:padding-left "1em"
                          :padding-right "1em"}
                  :on-submit (fn [e]
                               (.preventDefault e)
                               (let [value (cljs.reader/read-string @input-value)]
                                 (cond (= value
                                          @current-page)
                                       true
                                       (not= (type value)
                                             (type 1))
                                       (do (js/alert (str "This is not a valid page number:" value))
                                           ;; reset the value back to what it was
                                           ;; ?
                                           (reset! input-value @current-page)
                                           )
                                       (not (<= 1 value total-pages))
                                       (do (js/alert (str "This number is outside the page range: " value))
                                           ;; reset the value back to what it was
                                           (reset! input-value @current-page)
                                           )
                                       (<= 1 value total-pages)
                                       (do (reset! current-page value)
                                           (when on-click (on-click))))))}
           [:div "Page "
            [TextInput {:value input-value
                        :on-change #(reset! input-value (-> %
                                                            (aget "target")
                                                            (aget "value")))}]
            (str " of " total-pages)]]
          [:div {:class "ui buttons"}
           [:div {:class (if (= @current-page total-pages)
                           "ui tiny icon button disabled"
                           "ui tiny icon button")
                  :on-click (fn [e]
                              (.preventDefault e)
                              (let [new-current-page (+ (last displayed-pages) 1)]
                                (if (> new-current-page total-pages)
                                  (reset! current-page total-pages)
                                  (reset! current-page new-current-page)))
                              (when on-click (on-click)))}
            [:i {:class "angle right icon"}]
            "Next"]
           [:div {:class (if (= @current-page total-pages)
                           "ui tiny icon button disabled"
                           "ui tiny icon button")
                  :on-click (fn [e]
                              (.preventDefault e)
                              (reset! current-page total-pages)
                              (when on-click (on-click)))}
            [:i {:class "angle double right icon"}]
            "Last"]]]]))))

(defn ArticleSummary
  "Display an article summary item"
  [article item-idx]
  (let [{:keys [uid title authors source pubdate volume pages elocationid]} article]
    [:div.ui.segment
     item-idx [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/"  uid)
                   :target "_blank"}
               title]
     [:p {:style {:font-weight "bold"}} (clojure.string/join ", " (mapv :name authors))]
     [:p (str source ". " pubdate
              (when-not (empty? volume)
                (str "; " volume ":" pages))
              ". " elocationid ".")]
     [:p (str "PMID: " uid)]
     [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed?linkname=pubmed_pubmed&from_uid=" uid) } "Similar articles"]]))

(defn SearchItemsCount
  "Display the total amount of items for a search term as well as the current range being viewed"
  [count page-number pmids-per-page]
  [:div
   [:br]
   [:h3 "Search Results"]
   [:div [:a {:href "#"
              :on-click (fn [event]
                          (.preventDefault event)
                          (.log js/console "I would add articles"))}
          "Add Articles from Search Results to Project"]]
   [:h4 {:id "items-count"}
    "Items: "
    ;; only display total items when there is just a page's
    (when (< count pmids-per-page)
      [:span count])
    ;; show item numbers and total count when
    (when (>= count pmids-per-page)
      [:span (str (+ 1 (* (- page-number 1) pmids-per-page)) " to "
                  (let [max-page (* page-number pmids-per-page)]
                    (if (> max-page count)
                      count
                      max-page)) " of " count)])]])

(defn SearchBar
  "The search input for a pubmed query"
  [state]
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])
        fetch-results (fn [event]
                        (.preventDefault event)
                        (reset! current-search-term @on-change-search-term)
                        (reset! page-number 1)
                        (dispatch [:require [:pubmed-search @current-search-term 1]]))]
    (fn [props]
      [:form {:on-submit fetch-results
              :id "search-bar"}
       [:div.ui.fluid.icon.input
        [:input {:type "text"
                 :placeholder "PubMed Search..."
                 :on-change (fn [event]
                              (reset! on-change-search-term (-> event
                                                                (aget "target")
                                                                (aget "value"))))}]
        [:i.inverted.circular.search.link.icon
         {:on-click fetch-results}]]])))

(defn SearchResultArticles
  "Display articles of a search result"
  [state]
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (r/cursor state [:page-number])
        pmids-per-page (r/cursor state [:pmids-per-page])]
    (fn [props]
      (let [search-results (subscribe [:pubmed/search-term-result @current-search-term])]
        (when-not (nil? (:count @search-results))
          [:div
           [SearchItemsCount (:count @search-results) @page-number @pmids-per-page]
           [SearchResultArticlesPager {:total-pages (Math/ceil (/ (get-in @search-results [:count]) @pmids-per-page))
                                       :current-page page-number
                                       :on-click (fn []
                                                   (dispatch [:require [:pubmed-search @current-search-term @page-number]]))}]
           [:br]
           (if-not (empty? (get-in @search-results [:pages @page-number :summaries]))
             [:div
              (doall (map-indexed (fn [idx pmid]
                                    ^{:key pmid}
                                    [:div
                                     [ArticleSummary (get-in @search-results [:pages @page-number :summaries pmid])
                                      (str (+ (+ idx 1) (* (- @page-number 1) @pmids-per-page)) ". ")]
                                     [:br]])
                                  (get-in @search-results [:pages @page-number :pmids])))
              [SearchResultArticlesPager {:total-pages (Math/ceil (/ (get-in @search-results [:count]) @pmids-per-page))
                                          :current-page page-number
                                          :on-click (fn []
                                                      (dispatch [:require [:pubmed-search @current-search-term @page-number]]))}]]
             [:div.ui.active.centered.loader.inline
              [:div.ui.loader]])])))))

(defn PubmedSearchLink
  "Return a link on PubMed for the current search term"
  [state]
  (let [on-change-search-term (r/cursor state [:on-change-search-term])]
    (fn [props]
      [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/?"
                      (http-client/generate-query-string
                       {:term @on-change-search-term}))
           :target "_blank"}
       [:br]
       "Search on PubMed.gov"])))

(defn SearchResult [state]
  "Display pubmed search results, if any"
  (let [current-search-term (r/cursor state [:current-search-term])
        page-number (r/cursor state [:page-number])
        pmids-per-page (r/cursor state [:pmids-per-page])]
    (fn [props]
      (let [search-results (subscribe [:pubmed/search-term-result @current-search-term])
            result-count (get-in @search-results [:count])]
        [:div
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
                (= (get-in @search-results [:count]) 0)
                (not @(subscribe [:loading? [:pubmed-search @current-search-term @page-number]])))
           [:div
            [:br]
            [:h3 "No documents match your search terms"]]
           :default
           [SearchResultArticles state])]))))

(defn SearchPanel [state]
  "A panel for searching pubmed"
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])]
    (fn [props]
      (let []
        [:div.create-project
         [:div.ui.segment
          [:h3.ui.dividing.header
           "Add Articles from PubMed Search"]
          [SearchBar state]
          [PubmedSearchLink state]
          [SearchResult state]]]))))

(defmethod panel-content [:pubmed-search] []
  (fn [child]
    [SearchPanel state]))
