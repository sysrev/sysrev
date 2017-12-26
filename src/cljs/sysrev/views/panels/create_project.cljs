(ns sysrev.views.panels.create-project
  (:require
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.panels.project.main :refer [project-header]]
   [sysrev.util :refer [full-size? mobile?]]
   [sysrev.shared.util :refer [in?]]))

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
    (.log js/console "TextInput rendered")
    [:input {:type "text"
             :value @value
             :defaultValue default-value
             :placeholder placeholder
             :on-change on-change}]))

(defn TablePager
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
      (.log js/console "Table Pager rendered")
      (when (> @current-page displayed-pages)
        (reset! current-page 1))
      (when (> total-pages 1)
        [:nav
         [:ul {:class "pagination"}
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! current-page 1)
                            (when on-click (on-click)))}
            ;; the symbol here is called a Guillemet
            ;; html character entity reference &laquo;
            "« First"]]
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click
                (fn [e]
                  (.preventDefault e)
                  (let [new-current-page (- (first displayed-pages) 1)]
                    (if (< new-current-page 1)
                      (reset! current-page 1)
                      (reset! current-page new-current-page)))
                  (when on-click (on-click)))}
            ;; html character entity reference &lsaquo;
            "‹ Prev"]]
          [:li
           [:form {:on-submit (fn [e]
                                (.preventDefault e)
                                (.log js/console "I submitted")
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
                                            (.log js/console "I reset the current-page")
                                            (when on-click (on-click))))))}
            [:p "Page "
             [TextInput {:value input-value
                         :on-change #(reset! input-value (-> %
                                                             (aget "target")
                                                             (aget "value")))}]]
            (str " of " total-pages)]]
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (let [new-current-page (+ (last displayed-pages) 1)]
                              (if (> new-current-page total-pages)
                                (reset! current-page total-pages)
                                (reset! current-page new-current-page)))
                            (when on-click (on-click)))}
            ;; html character entity reference &rsaquo;
            "Next ›"]]
          [:li {:class "page-item"}
           [:a {:href "#"
                :class "page-link"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! current-page total-pages)
                            (when on-click (on-click)))}
            ;; html character entity reference &raquo;
            "Last »"]]]]))))

(defn article-summary-item
  "Display an article summary item"
  [article item-idx]
  (let [{:keys [uid title authors source pubdate volume pages elocationid]} article]
    (.log js/console "article-summary-item rendered")
    [:div
     item-idx [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed/"  uid)}
               title]
     [:p (clojure.string/join ", " (mapv :name authors))]
     [:p (str source ". " pubdate
              (when-not (empty? volume)
                (str "; " volume ":" pages))
              ". " elocationid ".")]
     [:p (str "PMID: " uid)]
     [:a {:href (str "https://www.ncbi.nlm.nih.gov/pubmed?linkname=pubmed_pubmed&from_uid=" uid) } "Similar articles"]]))

(defn search-items-count
  "Display the total amount of items for a search term as well as the current range being viewed"
  [count page-number]
  [:div
   [:h3 "Search Results"]
   [:h4 "Items: "
    ;; only display total items when there is just a page's
    (when (< count 20)
      [:span count])
    ;; show item numbers and total count when
    (when (>= count 20)
      [:span (str (+ 1 (* (- page-number 1) 20)) " to " (let [max-page (* page-number 20)]
                                                          (if (> max-page count)
                                                            count
                                                            max-page)) " of " count)])]])

(defn search-panel []
  "A panel for search pubmed"
  (let [current-search-term (r/atom nil)
        on-change-search-term (r/atom nil)
        page-number (r/atom 1)]
    (fn [props]
      (let [search-results (subscribe [:pubmed/search-term-result @current-search-term])
            fetch-results (fn [event]
                            (.preventDefault event)
                            (reset! current-search-term @on-change-search-term)
                            ;; !! The need for this check should be eliminated if we can get !!
                            ;; !! sysrev.data.definitions :loaded? keyword to work properly  !!
                            ;; fetch only if results for the search term don't already exist
                            (when (nil? (get-in
                                         ;; remember: current-search-term has changed, you can not
                                         ;; use @search-results here!
                                         @(subscribe [:pubmed/search-term-result @current-search-term])
                                         [:count]))
                              (reset! page-number 1)
                              (dispatch [:require [:pubmed-search @current-search-term 1]])))]
        (.log js/console "search panel rendered")
        [:div.create-project
         [:div.ui.segment
          [:h3.ui.dividing.header
           "Create a New Project"]
          [:form {:on-submit fetch-results}
           [:div.ui.fluid.icon.input
            [:input {:type "text"
                     :placeholder "PubMed Search..."
                     :on-change (fn [event]
                                  (reset! on-change-search-term (-> event
                                                                    (aget "target")
                                                                    (aget "value"))))}]
            [:i.inverted.circular.search.link.icon
             {:on-click fetch-results}]]]
          [:h3
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
             "No documents match your search terms"
             ;; the search term is populated and there
             ;; are results to be displayed
             (and (not (nil? @current-search-term))
                  (not (empty? (get-in @search-results [:pages @page-number :summaries]))))
             [:div
              (.log js/console "results are being displayed")
              [search-items-count (:count @search-results) @page-number]
              [TablePager {:total-pages (Math/ceil (/ (get-in @search-results [:count]) 20))
                           :current-page page-number
                           :on-click (fn []
                                       (dispatch [:require [:pubmed-search @current-search-term @page-number]]))}]
              [:br]
              (doall (map-indexed (fn [idx pmid]
                                    ^{:key pmid}
                                    [:div
                                     [article-summary-item (get-in @search-results [:pages @page-number :summaries pmid])
                                      (str (+ (+ idx 1) (* (- @page-number 1) 20)) ". ")]
                                     [:br]
                                     [:br]
                                     [:br]
                                     ])
                                  (get-in @search-results [:pages @page-number :pmids])))
              [TablePager {:total-pages (Math/ceil (/ (get-in @search-results [:count]) 20))
                           :current-page page-number
                           :on-click (fn []
                                       (dispatch [:require [:pubmed-search @current-search-term @page-number]]))}]]
             )]]]))))

(defmethod panel-content [:create-project] []
  (fn [child]
    [search-panel]))
