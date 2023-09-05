(ns sysrev.views.article-list.core
  (:require [clojure.string :as str]
            [re-frame.core :refer
             [dispatch dispatch-sync reg-event-fx reg-fx reg-sub subscribe trim-v]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :as data]
            [sysrev.macros :refer-macros [with-loader]]
            [sysrev.util :as util :refer [css in? index-by]]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.filters :as f]
            [sysrev.views.components.core :as ui]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.labels :as labels]
            [sysrev.views.panels.user.profile :refer [Avatar UserPublicProfileLink]]))

(defn- ArticleListNavHeader [context]
  (let [count-now @(al/sub-article-count context)
        count-cached @(al/sub-article-count (al/cached context))
        recent-action @(subscribe [::al/get context [:recent-nav-action]])]
    [:div.ui.segment.article-nav
     [ListPager
      {:panel (:panel context)
       :instance-key [:article-list]
       :offset @(subscribe [::al/display-offset context])
       :total-count count-now
       :items-per-page (al/get-display-count)
       :item-name-string "articles"
       :set-offset #(do (dispatch-sync [::al/set context [:display-offset] %])
                        (al/reload-list-data context))
       :on-nav-action (fn [action _offset]
                        (dispatch-sync [::al/set-recent-nav-action context action])
                        (dispatch-sync [::al/set-active-article context nil]))
       :recent-nav-action recent-action
       :loading? (or ((comp not nil?) recent-action)
                     (data/loading? #{:project/article-list
                                      :project/article-list-count}))
       :message-overrides {:offset @(subscribe [::al/display-offset (al/cached context)])
                           :total-count count-cached}}]]))

(defn- AnswerCellIcon [value]
  [:i {:class (css [(true? value)   "green circle plus"
                    (false? value)  "orange circle minus"
                    :else           "grey question mark"]
                   "icon answer-cell")}])

(defn- AnswerCell [article-id labels answer-class resolve]
  [:div.ui.divided.list
   (doall (for [entry labels]
            (let [{:keys [user-id inclusion]} entry
                  username @(subscribe [:user/username user-id])]
              (when (or (not= answer-class "resolved")
                        (= user-id (:user-id resolve)))
                [:div.item.answer-cell {:key [:answer article-id user-id]}
                 [:div.content>div.header>div.flex-wrap
                  [Avatar {:user-id user-id}]
                  [UserPublicProfileLink {:user-id user-id :username username}]
                  [AnswerCellIcon inclusion]]]))))])

(defn ArticleLabelsNotes [context article _full-size?]
  (let [self-id @(subscribe [:self/user-id])
        {:keys [show-labels show-notes self-only show-unconfirmed]}
        @(subscribe [::al/display-options (al/cached context)])
        {:keys [article-id labels notes]} article
        notes (cond->> notes
                self-only (filterv #(= (:user-id %) self-id)))
        labels (cond->> labels
                 self-only (filterv #(= (:user-id %) self-id))
                 (not show-unconfirmed)
                 (filterv #(not (in? [0 nil] (:confirm-time %)))))
        users-labels (group-by :user-id labels)
        users-notes (index-by :user-id notes)
        resolver-id @(subscribe [:article/resolve-user-id article-id])]
    [:div.ui.segment.article-labels
     (doall
      (for [user-id (->> [(keys users-labels)
                          (keys users-notes)]
                         (apply concat) distinct)]
        (let [user-labels (when show-labels (index-by :label-id (get users-labels user-id)))
              user-note (when show-notes (get users-notes user-id))
              user-name @(subscribe [:user/username user-id])
              resolved? (= user-id resolver-id)]
          (when (or user-note (seq user-labels))
            ^{:key [:user-labels user-id]}
            [labels/LabelValuesView user-labels
             article-id
             :user-name user-name
             :note user-note
             :resolved? resolved?]))))]))

(defn- ArticleListEntry [context article full-size?]
  (let [self-id @(subscribe [:self/user-id])
        {:keys [show-inclusion show-labels show-notes self-only
                show-unconfirmed]} @(subscribe [::al/display-options (al/cached context)])
        {:keys [recent-article active-article]} @(subscribe [::al/get (al/cached context)])
        overall-id @(subscribe [:project/overall-label-id])
        {:keys [article-id primary-title labels notes
                consensus updated-time resolve]} article
        notes (cond->> notes
                self-only (filterv #(= (:user-id %) self-id)))
        labels (cond->> labels
                 self-only (filterv #(= (:user-id %) self-id))
                 @(subscribe [:self/blinded?]) (filterv #(= (:user-id %) self-id))
                 (not show-unconfirmed) (filterv #(not (in? [0 nil] (:confirm-time %)))))
        consensus-labels (->> labels (filterv #(not (in? [0 nil] (:confirm-time %)))))
        overall-labels (->> consensus-labels (filter #(= (:label-id %) overall-id)))
        active? (and active-article (= article-id active-article))
        recent? (and recent-article (= article-id recent-article))
        answer-class (css (or (some-> consensus name) "conflict"))
        labels? (and (not active?) (or (and show-labels (not-empty labels))
                                       (and show-notes (not-empty notes))))
        inclusion-column? (and show-inclusion (not-empty overall-labels))
        link-props {:href (al/get-base-uri context article-id)
                    :on-click (util/wrap-user-event
                               #(dispatch [:article-list/set-recent-article context article-id]))}
        time-label #(when (some-> updated-time (not= 0))
                      [ui/UpdatedTimeLabel (util/time-from-epoch updated-time) true])]
    [:div.row
     [:div.sixteen.wide.column.article-entry
      [:div.ui.middle.aligned.grid.article-main>div.row
       [:a.column.article-title
        (-> {:class (css [(not full-size?) "eleven" inclusion-column? "twelve" :else "sixteen"]
                         "wide" [recent? "active"])}
            (merge link-props))
        [:div.flex-wrap
         #_ [:i.fitted.center.aligned.right.chevron.icon]
         [:div.article-title primary-title (when-not full-size? [time-label])]
         (when full-size? [time-label])]]
       (when inclusion-column?
         [:div.article-answers
          {:class (css answer-class [full-size? "four" :else "five"] "wide column")}
          [:div.answer-cell [AnswerCell article-id overall-labels answer-class resolve]]])]
      (when (and full-size? labels?)
        [:div.article-labels [ArticleLabelsNotes context article full-size?]])]]))

(defn- ArticleListContentRows [context articles]
  (let [{:keys [recent-article]} @(subscribe [::al/get (al/cached context)])
        {:keys [show-labels show-notes]} @(subscribe [::al/display-options (al/cached context)])
        project-id @(subscribe [:active-project-id])
        full-size? (util/full-size?)]
    [:<>
     (doall
       (->> articles
            (map-indexed
              (fn [i {:keys [article-id labels notes] :as article}]
                (let [recent? (= article-id recent-article)
                      loading? (and (data/loading? [:article project-id article-id])
                                    (= @active-route (al/get-base-uri context article-id)))
                      labels? (or show-labels show-notes)
                      first? (= i 0)
                      last? (= i (dec (count articles)))]
                  (doall
                    (list
                      [:div.ui.middle.aligned.grid.segment.article-list-article
                       {:key [:list-row article-id]
                        :class (css [recent? "active"] [labels? "with-labels"]
                                    [first? "first"] [last? "last"])}
                       [:div.ui.inverted.dimmer {:class (css [loading? "active"])}
                        [:div.ui.loader]]
                       [ArticleListEntry (al/cached context) article full-size?]])))))))]))

(defn- ArticleListContentDataTable [context articles]
  (let [{:keys [show-labels show-notes]}
        @(subscribe [::al/display-options (al/cached context)])
        labels @(subscribe [:project/labels-raw])
        label->type #(deref (subscribe [:label/value-type "na" (:label-id %)]))
        article-columns (filter some?
                                [{:key :id :display "Article ID" :get-fn :article-id}
                                 {:key :title :display "Title" :get-fn :primary-title}
                                 (when show-notes
                                   {:key :notes :display "Notes" :get-fn :notes})])
        user-columns [{:key :user :display "User" :get-fn #(deref (subscribe [:user/username %]))}]
        label-columns (map (fn [label]
                             {:key (:label-id label) :display (:short-label label)
                              :label label
                              :get-fn (fn [{:keys [label-id answer] :as label}]
                                        (cond
                                          (= (label->type label) "annotation")
                                          [labels/AnnotationLabelAnswerTag {:annotation-label-id label-id
                                                                            :answer answer}]

                                          (vector? answer)
                                          (str/join ", " answer)

                                          :else
                                          (str answer)))})
                           (remove #(contains? #{"group" "annotation"} (label->type %)) (vals labels)))
        columns (concat article-columns
                        (when show-labels user-columns)
                        (when show-labels label-columns))]
    [:div.overflow-x-auto
     [:table.ui.very.compact.table.articles-data-table
      [:thead
       [:tr
        (doall
         (for [column columns] ^{:key (:key column)}
           [:th {:class (when (keyword? (:key column))
                          (name (:key column)))}
            (:display column)]))]]
      [:tbody
       (doall
         (mapcat
           (fn [article]
             (let [article-labels (remove #(contains? #{"group" "annotation"} (label->type %))
                                          (:labels article))
                   answers (group-by :user-id article-labels)]
               (if (or (not show-labels) (empty? answers))
                 (list
                  [:tr {:key (str (:article-id article))}
                   (doall
                    (for [column article-columns] ^{:key (:key column)}
                      [:td {:class (name (:key column))}
                       ((:get-fn column) article)]))
                   (when show-labels
                     (doall
                      (for [column user-columns] ^{:key (:key column)}
                        [:td])))
                   (when show-labels
                     (doall
                      (for [column label-columns] ^{:key (:key column)}
                        [:td])))])
                 (map-indexed
                  (fn [idx [user-id answers]]
                    [:tr {:key (str idx "-" (:article-id article) "-" user-id)}
                     (doall
                      (for [column article-columns] ^{:key (:key column)}
                        [:td {:class (name (:key column))}
                         (when (zero? idx)
                           ((:get-fn column) article))]))
                     (doall
                      (for [column user-columns] ^{:key (:key column)}
                        [:td {:class (name (:key column))}
                         ((:get-fn column) user-id)]))
                     (doall
                      (for [column label-columns]
                        (let [answer (some #(when (= (:label-id %) (get-in column [:label :label-id])) %)
                                           answers)]
                          [:td {:key (:key column)}
                           [:div.label-value
                            ((:get-fn column) answer)]])))])
                  answers))))
          articles))]]]))

(defn- ArticleListContent [context options]
  (let [articles @(al/sub-articles (al/cached context))
        recent-nav-action @(subscribe [::al/get context [:recent-nav-action]])
        list-loading? (or (= recent-nav-action :refresh)
                          (= recent-nav-action :transition))]
    [:div.ui.segments.article-list-segments
     [:div.ui.dimmer {:class (css [list-loading? "active"])}
      [:div.ui.loader]]
     [ArticleListNavHeader context]
     (case (:display-mode options)
       :data [ArticleListContentDataTable context articles]
       ;; else
       [ArticleListContentRows context articles])]))

(defn- MultiArticlePanel [context options]
  (let [expanded? @(subscribe [::al/display-options (al/cached context) :expand-filters])
        count-item (subscribe [::al/count-query (al/cached context)])
        data-item (subscribe [::al/articles-query (al/cached context)])]
    [:div.article-list-view
     (al/update-ready-state context)
     (with-loader [@count-item @data-item] {}
       (if (util/full-size?)
         [:div.ui.grid.article-list-grid>div.row
          [:div.column.filters-column {:class (css [expanded? "five" :else "one"] "wide")}
           [f/ArticleListFiltersColumn context expanded?]]
          [:div.column.content-column {:class (css [expanded? "eleven" :else "fifteen"] "wide")}
           [:div.ui.form [:div.field>div.fields>div.sixteen.wide.field
                          [f/TextSearchInput context]]]
           [ArticleListContent context options]]]
         [:div
          ;; FIX: add filters interface for mobile/tablet
          #_ [f/ArticleListFiltersRow context]
          [ArticleListContent context options]]))]))

(defn- require-all-data [context]
  (when (and (not (al/have-data? context))
             (nil? @(subscribe [::al/get context [:recent-nav-action]])))
    (dispatch-sync [::al/set-recent-nav-action context :transition]))
  (dispatch [:require @(subscribe [::al/count-query context])])
  (dispatch [:require @(subscribe [::al/articles-query context])])
  (when (not-empty @(subscribe [::al/ready-state context]))
    (dispatch [:require @(subscribe [::al/count-query (al/cached context)])])
    (dispatch [:require @(subscribe [::al/articles-query (al/cached context)])])))

(defn ArticleListPanel [context options]
  [:div.article-list-toplevel
   (require-all-data context)
   [MultiArticlePanel context options]])

(reg-event-fx :article-list/set-recent-article [trim-v]
              (fn [{:keys [db]} [context article-id]]
                {:db (al/set-state db context [:recent-article] article-id)}))

(reg-sub :article-list/context
         :<- [:active-panel]
         :<- [:sysrev.views.panels.project.articles/article-list-context]
         :<- [:sysrev.views.panels.project.articles-data/article-list-context]
         (fn [[active-panel a-context d-context] [_ panel]]
           (case (or panel active-panel)
             [:project :project :articles]       a-context
             [:project :project :articles-data]  d-context
             nil)))

(defn load-settings-and-navigate
  "Loads article list settings and navigates to the page from another panel,
  while maintaining clean browser navigation history for Back/Forward."
  [context {:keys [filters display sort-by sort-dir] :as settings}]
  (let [context (or context @(subscribe [:article-list/context]))]
    (dispatch [:article-list/load-settings context settings])
    (dispatch [::al/navigate context :redirect? false])
    (util/scroll-top)))

(defn load-source-filters
  "Loads settings for filtering by article source, and navigates to articles page."
  [context & {:keys [source-ids]}]
  (load-settings-and-navigate
   context
   {:filters (->> source-ids (mapv #(do {:source {:source-ids [%]}})))
    :display {:show-inclusion true}
    :sort-by :content-updated
    :sort-dir :desc}))

(reg-fx :article-list/load-source-filters
        (fn [[context source-ids]] (load-source-filters context :source-ids source-ids)))

(defn load-export-settings
  "Loads default settings for file export type, then navigates to
  articles page if navigate is true."
  [context export-type navigate]
  (let [context (or context @(subscribe [:article-list/context]))]
    (dispatch [:article-list/load-settings context
               {:filters (get f/export-type-default-filters export-type)
                :display {:show-inclusion true, :expand-export (name export-type)}
                :sort-by :content-updated
                :sort-dir :desc}])
    (when navigate
      (dispatch [::al/navigate context :redirect? false])
      (util/scroll-top))))

(reg-fx :article-list/load-export-settings
        (fn [[context export-type navigate]]
          (load-export-settings context export-type navigate)))

(reg-event-fx :article-list/load-export-settings [trim-v]
              (fn [_ [context export-type navigate]]
                {:article-list/load-export-settings [context export-type navigate]}))
