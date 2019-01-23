(ns sysrev.views.article-list.core
  (:require [clojure.string :as str]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [sysrev.base :refer [use-new-article-list?]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer
             [active-panel active-project-id]]
            [sysrev.state.ui :as ui-state]
            [sysrev.views.article :refer [ArticleInfo]]
            [sysrev.views.review :refer [LabelEditor]]
            [sysrev.views.components :as ui]
            [sysrev.views.list-pager :refer [ListPager]]
            [sysrev.views.labels :as labels]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.filters :as f]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(reg-sub-raw
 ::prev-next-article-ids
 (fn [_ [_ context]]
   (reaction
    (let [articles @(al/sub-articles context)
          active-id @(subscribe [::al/get context [:active-article]])
          visible-ids (map :article-id articles)]
      (when (in? visible-ids active-id)
        {:prev-id
         (->> visible-ids
              (take-while #(not= % active-id))
              last)
         :next-id
         (->> visible-ids
              (drop-while #(not= % active-id))
              (drop 1)
              first)})))))

(reg-sub
 ::resolving-allowed?
 (fn [[_ context article-id]]
   [(subscribe [::al/get context [:active-article]])
    (subscribe [:article/review-status article-id])
    (subscribe [:member/resolver?])
    (subscribe [::al/display-options context])])
 (fn [[active-id review-status resolver? display]
      [_ context article-id]]
   (let [{:keys [self-only]} display]
     (when (= article-id active-id)
       (boolean
        (and (not self-only)
             (= :conflict review-status)
             resolver?))))))

(reg-sub
 ::editing-allowed?
 (fn [[_ context article-id]]
   [(subscribe [::al/get context [:active-article]])
    (subscribe [::resolving-allowed? context article-id])
    (subscribe [:article/user-status article-id])])
 (fn [[active-id can-resolve? user-status]
      [_ context article-id]]
   (when (= article-id active-id)
     (boolean
      (or can-resolve?
          (in? [:confirmed :unconfirmed] user-status))))))

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
       :on-nav-action
       (fn [action offset]
         (dispatch-sync [::al/set-recent-nav-action context action])
         (dispatch-sync [::al/set-active-article context nil]))
       :recent-nav-action recent-action
       :loading? (or ((comp not nil?) recent-action)
                     (loading/any-loading? :only :project/article-list)
                     (loading/any-loading? :only :project/article-list-count))
       :message-overrides
       {:offset @(subscribe [::al/display-offset (al/cached context)])
        :total-count count-cached}}]]))

(defn- AnswerCellIcon [value]
  (case value
    true  [:i.green.circle.plus.icon]
    false [:i.orange.circle.minus.icon]
    [:i.grey.question.mark.icon]))

(defn- AnswerCell [article-id labels answer-class]
  [:div.ui.divided.list
   (doall
    (map (fn [entry]
           (let [{:keys [user-id inclusion]} entry]
             (when (or (not= answer-class "resolved")
                       (:resolve entry))
               [:div.item {:key [:answer article-id user-id]}
                (AnswerCellIcon inclusion)
                [:div.content>div.header
                 @(subscribe [:user/display user-id])]])))
         labels))])

(defn- ArticleContent [context article-id]
  (let [editing-allowed? @(subscribe [::editing-allowed? context article-id])
        resolving-allowed? @(subscribe [::resolving-allowed? context article-id])
        editing? @(subscribe [:article-list/editing? context article-id])
        {:keys [self-only]}
        @(subscribe [::al/display-options (al/cached context)])]
    [:div {:style {:width "100%"}}
     [ArticleInfo article-id
      :show-labels? true
      :private-view? self-only
      :context :article-list]
     (cond editing?
           [LabelEditor article-id]

           editing-allowed?
           [:div.ui.segment
            [:div.ui.fluid.button
             {:on-click
              (util/wrap-user-event
               #(dispatch [:review/enable-change-labels
                           article-id (:panel context)]))}
             (if resolving-allowed? "Resolve Labels" "Change Labels")]])]))

(defn ArticleLabelsNotes [context article full-size?]
  (let [self-id @(subscribe [:self/user-id])
        {:keys [show-labels show-notes self-only show-unconfirmed]}
        @(subscribe [::al/display-options (al/cached context)])
        {:keys [labels notes]} article
        notes (cond->> notes
                self-only (filterv #(= (:user-id %) self-id)))
        labels (cond->> labels
                 self-only (filterv #(= (:user-id %) self-id))
                 (not show-unconfirmed)
                 (filterv #(not (in? [0 nil] (:confirm-time %)))))
        users-labels (group-by :user-id labels)
        users-notes (group-by :user-id notes)
        self-id @(subscribe [:self/user-id])]
    [:div.ui.segment.article-labels
     (doall
      (for [user-id (->> [(keys users-labels)
                          (keys users-notes)]
                         (apply concat) distinct)]
        (let [user-labels (if show-labels
                            (->> (get users-labels user-id)
                                 (group-by :label-id)
                                 (map-values first))
                            {})
              user-note (when show-notes
                          (->> (get users-notes user-id) first))
              user-name @(subscribe [:user/display user-id])]
          (when (or user-note (not-empty user-labels))
            ^{:key [:user-labels user-id]}
            [labels/label-values-component
             user-labels
             :user-name user-name
             :notes (when user-note
                      {(:name user-note) (:content user-note)})]))))]))

(defn- ArticleListEntry
  [context article full-size?]
  (let [self-id @(subscribe [:self/user-id])
        {:keys [show-inclusion show-labels show-notes self-only show-unconfirmed]}
        @(subscribe [::al/display-options (al/cached context)])
        active-article @(subscribe [::al/get context [:active-article]])
        overall-id @(subscribe [:project/overall-label-id])
        {:keys [article-id primary-title labels notes
                consensus updated-time]} article
        notes (cond->> notes
                self-only (filterv #(= (:user-id %) self-id)))
        labels (cond->> labels
                 self-only (filterv #(= (:user-id %) self-id))
                 (not show-unconfirmed)
                 (filterv #(not (in? [0 nil] (:confirm-time %)))))
        consensus-labels
        (->> labels
             (filterv #(not (in? [0 nil] (:confirm-time %)))))
        overall-labels (->> consensus-labels (filter #(= (:label-id %) overall-id)))
        active? (and active-article (= article-id active-article))
        answer-class (if consensus (name consensus) "conflict")
        labels? (and (not active?)
                     (or (and show-labels (not-empty labels))
                         (and show-notes (not-empty notes))))
        inclusion-column? (and show-inclusion (not-empty overall-labels))]
    (if full-size?
      ;; non-mobile view
      [:div.ui.row
       [:div.sixteen.wide.column.article-entry
        [:div.ui.middle.aligned.grid.article-main
         [:div.row
          [:div.column.article-title
           {:class (if inclusion-column? "thirteen wide" "sixteen wide")}
           [:div.ui.middle.aligned.grid
            [:div.row
             [:div.fourteen.wide.column
              {:style {:padding-right "0"}}
              [:div.ui.middle.aligned.grid>div.row
               [:div.one.wide.center.aligned.column
                [:div.ui.fluid.labeled.center.aligned.button
                 [:i.fitted.center.aligned
                  {:class (str (if active? "down" "right")
                               " chevron icon")
                   :style {:width "100%"}}]]]
               [:div.fifteen.wide.column
                {:style {:padding-left "0"}}
                [:span.article-title primary-title]]]]
             [:div.two.wide.right.aligned.column.article-updated-time
              (when (and updated-time (not= updated-time 0))
                [ui/updated-time-label
                 (util/time-from-epoch updated-time) true])]]]]
          (when inclusion-column?
            [:div.three.wide.center.aligned.middle.aligned.column.article-answers
             {:class answer-class}
             [:div.ui.middle.aligned.grid>div.row>div.column
              [AnswerCell article-id overall-labels answer-class]]])]]
        (when labels?
          [:div.article-labels
           [ArticleLabelsNotes context article full-size?]])]]
      ;; mobile view
      [:div.row
       [:div.eleven.wide.column.article-title
        [:span.article-title primary-title]
        (when (and updated-time (not= updated-time 0))
          [ui/updated-time-label
           (util/time-from-epoch updated-time) true])]
       [:div.five.wide.center.aligned.middle.aligned.column.article-answers
        (when (not-empty overall-labels)
          {:class answer-class})
        (when (not-empty overall-labels)
          [:div.ui.middle.aligned.grid>div.row>div.column
           [AnswerCell article-id overall-labels answer-class]])]])))

(defn- ArticleListContent [context]
  (let [{:keys [recent-article active-article]}
        @(subscribe [::al/get (al/cached context)])
        {:keys [show-labels show-notes]}
        @(subscribe [::al/display-options (al/cached context)])
        project-id @(subscribe [:active-project-id])
        articles @(al/sub-articles (al/cached context))
        recent-nav-action @(subscribe [::al/get context [:recent-nav-action]])
        loading? (or (= recent-nav-action :refresh)
                     (= recent-nav-action :transition))
        full-size? (util/full-size?)]
    [:div.ui.segments.article-list-segments
     [:div.ui.dimmer
      {:class (when loading? "active")}
      [:div.ui.loader]]
     (doall
      (concat
       (list
        ^{:key :article-nav-header}
        [ArticleListNavHeader context])
       (->>
        articles
        (map-indexed
         (fn [i {:keys [article-id labels notes] :as article}]
           (let [recent? (= article-id recent-article)
                 active? (= article-id active-article)
                 have? @(subscribe [:have? [:article project-id article-id]])
                 classes (if (or active? recent?) "active" "")
                 loading? (loading/item-loading? [:article project-id article-id])
                 labels? (or show-labels show-notes)
                 {:keys [next-id prev-id]}
                 @(subscribe [::prev-next-article-ids context])
                 go-next
                 (when next-id
                   #(dispatch-sync [::al/set-active-article context next-id]))
                 go-prev
                 (when prev-id
                   #(dispatch-sync [::al/set-active-article context prev-id]))
                 first? (= i 0)
                 last? (= i (dec (count articles)))]
             (doall
              (list
               [:a.ui.middle.aligned.grid.segment.article-list-article
                {:key [:list-row article-id]
                 :class (cond-> ""
                          ;; recent? (str " active")
                          active? (str " expanded")
                          labels? (str " with-labels")
                          first?  (str " first")
                          last?   (str " last"))
                 :on-click
                 (util/wrap-user-event
                  (if active?
                    #(dispatch-sync [::al/set-active-article context nil])
                    #(do (dispatch-sync [::al/set-display-option
                                         context :expand-filters false])
                         (dispatch-sync [::al/set-active-article context article-id]))))}
                [ArticleListEntry (al/cached context) article full-size?]]
               (when active?
                 (doall
                  (list
                   [:div.ui.middle.aligned.grid.segment.article-list-full-article
                    {:key [:article-row article-id]
                     :class (str (if recent? "active" "")
                                 " "
                                 (if loading? "article-loading" ""))}
                    (when (and loading? (not have?))
                      [:div.ui.active.inverted.dimmer
                       [:div.ui.loader]])
                    [ArticleContent (al/cached context) article-id]])))))))))))]))

(defn- ArticleListExpandedEntry [context article]
  (let [base-context (al/no-cache context)
        {:keys [article-id]} article
        project-id @(subscribe [:active-project-id])
        {:keys [recent-article active-article]} @(subscribe [::al/get context])
        recent? (= article-id recent-article)
        active? (= article-id active-article)
        have? @(subscribe [:have? [:article project-id article-id]])
        classes (if (or active? recent?) "active" "")
        loading? (loading/item-loading? [:article project-id article-id])
        full-size? (util/full-size?)]
    (doall
     (list
      [:a.ui.middle.aligned.grid.segment.article-list-article
       {:key [:list-row article-id]
        :class (str (if recent? "active" ""))
        :on-click
        (util/wrap-user-event
         (if active?
           #(dispatch-sync [::al/set-active-article base-context nil])
           #(dispatch-sync [::al/set-active-article base-context article-id])))}
       [ArticleListEntry context article full-size?]]
      (when active?
        [:div.ui.middle.aligned.grid.segment.article-list-full-article
         {:key [:article-row article-id]
          :class (str (if recent? "active" "")
                      " "
                      (if loading? "article-loading" ""))}
         (when (and loading? (not have?))
           [:div.ui.active.inverted.dimmer
            [:div.ui.loader]])
         [ArticleContent context article-id]])))))

(defn- SingleArticlePanel [context]
  (let [active-article @(subscribe [::al/get context [:active-article]])
        project-id @(subscribe [:active-project-id])
        title @(subscribe [:article/title active-article])]
    (with-loader [[:article project-id active-article]] {}
      [:div>div.article-list-view
       [:div.ui.segments.article-list-segments
        (ArticleListExpandedEntry
         context {:article-id active-article
                  :primary-title title})]])))

(defn- MultiArticlePanel [context]
  (let [ready-state @(subscribe [::al/ready-state context])
        expand-filters @(subscribe [::al/display-options
                                    (al/cached context) :expand-filters])
        count-item (subscribe [::al/count-query (al/cached context)])
        data-item (subscribe [::al/articles-query (al/cached context)])
        active-article @(subscribe [::al/get (al/cached context) [:active-article]])
        expanded? (and expand-filters #_ (nil? active-article))]
    [:div.article-list-view
     (al/update-ready-state context)
     (with-loader [@count-item @data-item] {}
       (if (util/full-size?)
         [:div.ui.grid.article-list-grid
          [:div.row
           [:div.column.filters-column
            {:class (if expanded? "four wide" "one wide")}
            [ui/WrapFixedVisibility 10
             [f/ArticleListFiltersColumn context expanded?]]]
           [:div.column.content-column
            {:class (if expanded? "twelve wide" "fifteen wide")}
            [:div.ui.form
             [:div.field>div.fields>div.sixteen.wide.field
              [f/TextSearchInput context]]]
            [ArticleListContent context]]]]
         [:div
          ;; TODO: make filters interface for mobile/tablet
          #_ [f/ArticleListFiltersRow context]
          [ArticleListContent context]]))]))

(defn- require-all-data [context]
  (when (and (not (al/have-data? context))
             (nil? @(subscribe [::al/get context [:recent-nav-action]])))
    (dispatch-sync [::al/set-recent-nav-action context :transition]))
  (dispatch [:require @(subscribe [::al/count-query context])])
  (dispatch [:require @(subscribe [::al/articles-query context])])
  (when (not-empty @(subscribe [::al/ready-state context]))
    (dispatch [:require @(subscribe [::al/count-query (al/cached context)])])
    (dispatch [:require @(subscribe [::al/articles-query (al/cached context)])])))

(defn ArticleListPanel [context]
  (let [single-article? @(subscribe [::al/get (al/cached context) [:single-article?]])]
    [:div.article-list-toplevel-new
     (require-all-data context)
     (if single-article?
       [SingleArticlePanel context]
       [MultiArticlePanel context])]))

(when use-new-article-list?
  ;; Gets id of article currently being individually displayed
  (reg-sub
   :article-list/article-id
   (fn [[_ context]]
     [(subscribe [:active-panel])
      (subscribe [::al/get context [:active-article]])])
   (fn [[active-panel article-id] [_ context]]
     (when (= active-panel (:panel context))
       article-id)))

  (reg-event-fx
   :article-list/set-recent-article
   [trim-v]
   (fn [{:keys [db]} [context article-id]]
     {:db (al/set-state db context [:recent-article] article-id)}))

  (reg-event-fx
   :article-list/set-active-article
   [trim-v]
   (fn [{:keys [db]} [context article-id]]
     {:dispatch [::al/set-active-article context article-id]}))

  (reg-sub
   :article-list/editing?
   (fn [[_ context article-id]]
     [(subscribe [::al/get context [:active-article]])
      (subscribe [::editing-allowed? context article-id])
      (subscribe [:article/user-status article-id])
      (subscribe [:review/change-labels? article-id (:panel context)])
      (subscribe [::al/display-options context])])
   (fn [[active-id can-edit? user-status change-labels? display]
        [_ context article-id]]
     (let [{:keys [self-only]} display]
       (when (= article-id active-id)
         (boolean
          (and can-edit?
               (or change-labels?
                   (and #_ self-only
                        (= user-status :unconfirmed)))))))))

  (reg-sub
   :article-list/resolving?
   (fn [[_ context article-id]]
     [(subscribe [:article-list/editing? context article-id])
      (subscribe [::resolving-allowed? context article-id])])
   (fn [[editing? resolving-allowed?]]
     (boolean
      (and editing? resolving-allowed?)))))
