(ns sysrev.views.article-list.core
  (:require [reagent.ratom :refer [reaction]]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw reg-event-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.views.article :refer [ArticleInfo]]
            [sysrev.views.review :as review]
            [sysrev.views.components.core :as ui]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.labels :as labels]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.article-list.filters :as f]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.util :as util :refer [in? css index-by]]
            [sysrev.macros :refer-macros [with-loader]]))

(reg-sub-raw ::prev-next-article-ids
             (fn [_ [_ context]]
               (reaction
                (let [articles @(al/sub-articles context)
                      active-id @(subscribe [::al/get context [:active-article]])
                      visible-ids (map :article-id articles)]
                  (when (in? visible-ids active-id)
                    {:prev-id (->> visible-ids (take-while #(not= % active-id)) last)
                     :next-id (->> visible-ids (drop-while #(not= % active-id)) (drop 1) first)})))))

(reg-sub ::resolving-allowed?
         (fn [[_ context article-id]]
           [(subscribe [::al/get context [:active-article]])
            (subscribe [:article/review-status article-id])
            (subscribe [:member/resolver?])
            (subscribe [::al/display-options context])])
         (fn [[active-id review-status resolver? display]
              [_ _context article-id]]
           (let [{:keys [self-only]} display]
             (when (= article-id active-id)
               (boolean (and (not self-only) (= :conflict review-status) resolver?))))))

(reg-sub-raw ::editing-allowed?
             (fn [_ [_ context article-id]]
               (reaction
                (let [{:keys [active-article]} @(subscribe [::al/get context])
                      self-id @(subscribe [:self/user-id])
                      project-id @(subscribe [:active-project-id])
                      ann-context {:class "abstract" :project-id project-id :article-id article-id}]
                  (when (= article-id active-article)
                    (boolean
                     (and self-id
                          @(subscribe [:self/member? project-id])
                          (or @(subscribe [::resolving-allowed? context article-id])
                              (in? [:confirmed :unconfirmed]
                                   @(subscribe [:article/user-status article-id]))
                              (seq @(subscribe [:annotator/user-annotations
                                                ann-context self-id]))))))))))

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
                     (loading/any-loading? :only :project/article-list)
                     (loading/any-loading? :only :project/article-list-count))
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
                  user-name @(subscribe [:user/display user-id])]
              (when (or (not= answer-class "resolved")
                        (= user-id (:user-id resolve)))
                [:div.item.answer-cell {:key [:answer article-id user-id]}
                 [:div.content>div.header>div.flex-wrap
                  [Avatar {:user-id user-id}]
                  [UserPublicProfileLink {:user-id user-id :display-name user-name}]
                  [AnswerCellIcon inclusion]]]))))])

(defn ChangeLabelsButton [context article-id & {:keys [sidebar]}]
  (let [editing? @(subscribe [:article-list/editing? context article-id])
        editing-allowed? @(subscribe [::editing-allowed? context article-id])
        resolving-allowed? @(subscribe [::resolving-allowed? context article-id])]
    (when (not editing?)
      [:div.ui.fluid.left.labeled.icon.button.primary.change-labels
       {:class (css [sidebar "small"] [resolving-allowed? "resolve-labels"])
        :style {:margin-top "1em"}
        :on-click (util/wrap-user-event
                   #(do (dispatch [:review/enable-change-labels article-id (:panel context)])
                        (dispatch [:set-review-interface :labels])))}
       [:i.pencil.icon]
       (cond resolving-allowed? "Resolve Labels"
             editing-allowed? "Change Labels"
             :else "Manually Add Labels")])))

(defn ArticleContent [context article-id]
  (let [editing? @(subscribe [:article-list/editing? context article-id])
        {:keys [self-only]} @(subscribe [::al/display-options (al/cached context)])
        resolving? @(subscribe [:review/resolving?])]
    [:div {:style {:width "100%"}}
     [ArticleInfo article-id
      :show-labels? true
      :private-view? self-only
      :context :article-list
      :change-labels-button (fn [] [ChangeLabelsButton context article-id])
      :resolving? resolving?]
     (when editing? [review/LabelAnswerEditor article-id])]))

(defn ArticleLabelsNotes [context article _full-size?]
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
        resolver-id @(subscribe [:article/resolve-user-id (:article-id article)])]
    [:div.ui.segment.article-labels
     (doall
      (for [user-id (->> [(keys users-labels)
                          (keys users-notes)]
                         (apply concat) distinct)]
        (let [user-labels (if show-labels
                            (index-by :label-id (get users-labels user-id))
                            {})
              user-note (when show-notes (first (get users-notes user-id)))
              user-name @(subscribe [:user/display user-id])
              resolved? (= user-id resolver-id)]
          (when (or user-note (not-empty user-labels))
            ^{:key [:user-labels user-id]}
            [labels/LabelValuesView
             user-labels
             :user-name user-name
             :notes (when user-note {(:name user-note) (:content user-note)})
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
                      [ui/updated-time-label (util/time-from-epoch updated-time) true])]
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

(defn- ArticleListContent [context]
  (let [{:keys [recent-article active-article]}
        @(subscribe [::al/get (al/cached context)])
        {:keys [show-labels show-notes]}
        @(subscribe [::al/display-options (al/cached context)])
        project-id @(subscribe [:active-project-id])
        articles @(al/sub-articles (al/cached context))
        recent-nav-action @(subscribe [::al/get context [:recent-nav-action]])
        list-loading? (or (= recent-nav-action :refresh)
                          (= recent-nav-action :transition))
        full-size? (util/full-size?)]
    [:div.ui.segments.article-list-segments
     [:div.ui.dimmer {:class (css [list-loading? "active"])}
      [:div.ui.loader]]
     (doall
      (concat
       (list ^{:key :article-nav-header}
             [ArticleListNavHeader context])
       (->> articles
            (map-indexed
             (fn [i {:keys [article-id labels notes] :as article}]
               (let [recent? (= article-id recent-article)
                     active? (= article-id active-article)
                     have? @(subscribe [:have? [:article project-id article-id]])
                     loading? (loading/item-loading? [:article project-id article-id])
                     labels? (or show-labels show-notes)
                     first? (= i 0)
                     last? (= i (dec (count articles)))]
                 (doall
                  (list
                   [:div.ui.middle.aligned.grid.segment.article-list-article
                    {:key [:list-row article-id]
                     :class (css [recent? "active"] [active? "expanded"] [labels? "with-labels"]
                                 [first? "first"] [last? "last"])}
                    [:div.ui.inverted.dimmer {:class (css [loading? "active"])}
                     [:div.ui.loader]]
                    [ArticleListEntry (al/cached context) article full-size?]]
                   (when active?
                     [:div.ui.middle.aligned.grid.segment.article-list-full-article
                      {:key [:article-row article-id]
                       :class (css [recent? "active"] [loading? "article-loading"])}
                      (when (and loading? (not have?))
                        [:div.ui.active.inverted.dimmer>div.ui.loader])
                      [ArticleContent (al/cached context) article-id]])))))))))]))

(defn- ArticleListExpandedEntry [context article]
  (let [base-context (al/no-cache context)
        {:keys [article-id]} article
        project-id @(subscribe [:active-project-id])
        {:keys [recent-article active-article]} @(subscribe [::al/get context])
        recent? (= article-id recent-article)
        active? (= article-id active-article)
        have? @(subscribe [:have? [:article project-id article-id]])
        loading? (loading/item-loading? [:article project-id article-id])
        full-size? (util/full-size?)]
    (doall
     (list
      [:a.ui.middle.aligned.grid.segment.article-list-article
       {:key [:list-row article-id]
        :class (css [recent? "active"])
        :on-click (util/wrap-user-event
                   #(dispatch-sync [::al/set-active-article
                                    base-context (if active? nil article-id)]))}
       [ArticleListEntry context article full-size?]]
      (when active?
        [:div.ui.middle.aligned.grid.segment.article-list-full-article
         {:key [:article-row article-id]
          :class (css [recent? "active"] [loading? "article-loading"])}
         (when (and loading? (not have?))
           [:div.ui.active.inverted.dimmer>div.ui.loader])
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
  (let [expanded? @(subscribe [::al/display-options (al/cached context) :expand-filters])
        count-item (subscribe [::al/count-query (al/cached context)])
        data-item (subscribe [::al/articles-query (al/cached context)])]
    [:div.article-list-view
     (al/update-ready-state context)
     (with-loader [@count-item @data-item] {}
       (if (util/full-size?)
         [:div.ui.grid.article-list-grid>div.row
          [:div.column.filters-column {:class (css [expanded? "five" :else "one"] "wide")}
           [f/ArticleListFiltersColumn context expanded?]
           #_ [ui/WrapFixedVisibility 10
               [f/ArticleListFiltersColumn context expanded?]]]
          [:div.column.content-column {:class (css [expanded? "eleven" :else "fifteen"] "wide")}
           [:div.ui.form [:div.field>div.fields>div.sixteen.wide.field
                          [f/TextSearchInput context]]]
           [ArticleListContent context]]]
         [:div
          ;; FIX: add filters interface for mobile/tablet
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
    [:div.article-list-toplevel
     (require-all-data context)
     (if single-article?
       [SingleArticlePanel context]
       [MultiArticlePanel context])]))

;; Gets id of article currently being individually displayed
(reg-sub :article-list/article-id
         (fn [[_ context]]
           [(subscribe [:active-panel])
            (subscribe [::al/get context [:active-article]])])
         (fn [[active-panel article-id] [_ context]]
           (when (= active-panel (:panel context))
             article-id)))

(reg-event-fx :article-list/set-recent-article [trim-v]
              (fn [{:keys [db]} [context article-id]]
                {:db (al/set-state db context [:recent-article] article-id)}))

(reg-event-fx :article-list/set-active-article [trim-v]
              (fn [{:keys [db]} [context article-id]]
                {:dispatch [::al/set-active-article context article-id]}))

(reg-sub :article-list/editing?
         (fn [[_ context article-id]]
           [(subscribe [::al/get context [:active-article]])
            (subscribe [:article/user-status article-id])
            (subscribe [:review/change-labels? article-id (:panel context)])])
         (fn [[active-id user-status change-labels?]
              [_ _context article-id]]
           (when (= article-id active-id)
             (boolean (or change-labels? (= user-status :unconfirmed))))))

(reg-sub :article-list/resolving?
         (fn [[_ context article-id]]
           [(subscribe [:article-list/editing? context article-id])
            (subscribe [::resolving-allowed? context article-id])])
         (fn [[editing? resolving-allowed?]]
           (boolean (and editing? resolving-allowed?))))
