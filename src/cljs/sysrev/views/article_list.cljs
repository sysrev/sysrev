(ns sysrev.views.article-list
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [use-new-article-list?]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav :refer [nav]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.shared.keywords :refer [canonical-keyword]]
            [sysrev.shared.article-list :refer
             [is-resolved? resolved-answer is-conflict? is-single? is-consistent?]]
            [sysrev.views.article :refer [article-info-view]]
            [sysrev.views.review :refer [label-editor-view]]
            [sysrev.views.components :refer
             [with-ui-help-tooltip ui-help-icon selection-dropdown
              three-state-selection-icons updated-time-label]]
            [sysrev.views.list-pager :refer [ListPager]]
            [sysrev.util :refer [full-size? mobile? nbsp time-from-epoch]]
            [sysrev.shared.util :as util :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmulti panel-defaults identity)
(defmethod panel-defaults :default [] nil)

(defn default-defaults []
  (let [overall-id @(subscribe [:project/overall-label-id])]
    {:options
     {:display-count (if (mobile?) 10 20)}
     :filters
     [{:label-id overall-id}]
     :sort-by :article-id
     :sort-dir :asc
     :offset 0
     :base-uri (fn [project-id]
                 (project-uri project-id "/articles"))
     :display
     {:expand-filters false
      :show-inclusion true
      :show-labels false
      :show-notes false}}))

(defn panel-state-path [panel]
  [:state :panels panel :article-list])

(defn panel-cursor [panel]
  (r/cursor app-db (panel-state-path panel)))

(s/def ::label-id (some-fn nil? uuid? transit/uuid?))
(s/def ::display-count (s/and integer? pos?))
(s/def ::offset (s/and integer? nat-int?))
(s/def ::base-uri fn?)
(s/def ::recent-article (s/nilable integer?))
(s/def ::active-article (s/nilable integer?))
(s/def ::panel (s/every keyword? :kind vector?))
(s/def ::recent-nav-action (s/nilable keyword?))
(s/def ::single-filter
  (s/and (s/keys :opt-un [::label-id])
         map? #(= 1 (count %))))
(s/def ::filters
  (s/and (s/every map? :kind vector?)
         #(every? (in? [:label-id :text-search])
                  (->> % (map keys) (apply concat)))))
(s/def ::sort-by
  (s/and keyword? (in? [:article-id :label-time])))
(s/def ::sort-dir
  (s/and keyword? (in? [:asc :desc])))
(s/def ::options
  (s/keys :req-un [::display-count]))
(s/def ::al-state
  (s/keys :req-un [::options ::filters ::offset ::base-uri
                   ::sort-by ::sort-dir]
          :opt-un [::recent-article ::active-article ::panel
                   ::recent-nav-action]))

(defn current-state [stateval defaults]
  (as-> (merge-with (fn [a b]
                      (if (and (or (nil? a) (map? a))
                               (or (nil? b) (map? b)))
                        (merge a b)
                        b))
                    (default-defaults) defaults stateval)
      cstate
      (do (when-not (s/valid? ::al-state cstate)
            (s/explain ::al-state cstate))
          cstate)))

(defn make-url-params [{:keys [filters] :as cstate}]
  (merge (apply merge-with vector filters)
         (select-keys cstate [:offset])))

(defn get-url-params []
  (let [{:keys [offset text-search]} (nav/get-url-params)]
    (cond-> []
      offset
      (conj [[:offset] (util/parse-integer offset)])
      (string? text-search)
      (conj [[:filters :text-search] text-search]))))

(defn- panel-base-uri [cstate]
  ((:base-uri cstate)
   @(subscribe [:active-project-id])))

(defn- article-uri [cstate article-id]
  (str (panel-base-uri cstate) "/" article-id))

(defn nav-list [cstate & {:keys [params redirect?]}]
  ((if redirect? nav/nav-redirect nav/nav)
   (panel-base-uri cstate) :params (merge (make-url-params cstate) params)))

(defn nav-article [cstate article-id & {:keys [params redirect?]}]
  ((if redirect? nav/nav-redirect nav/nav)
   (article-uri cstate article-id) :params (merge (make-url-params cstate) params)))

(defn merge-url-params [cstate]
  (let [params (get-url-params)
        filter-keys
        (->> params
             (map (fn [[kpath value]]
                    (when (= :filters (first kpath))
                      (second kpath))))
             (remove nil?)
             distinct)
        filters-init
        (->> (:filters cstate)
             (remove #(some (in? filter-keys) (keys %)))
             vec)]
    (reduce (fn [stateval f]
              (f stateval))
            (assoc cstate :filters filters-init)
            (map (fn [[kpath value]]
                   #(if (= :filters (first kpath))
                      (update-in
                       % [:filters]
                       (fn [filters]
                         (conj filters {(second kpath) value})))
                      (assoc-in % kpath value)))
                 params))))

(reg-event-fx
 :article-list/load-url-params
 [trim-v]
 (fn [{:keys [db]} [panel]]
   (let [path (panel-state-path panel)
         current (get-in db path)
         defaults (panel-defaults panel)
         cstate (current-state current defaults)]
     {:db (assoc-in db path (merge-url-params cstate))
      ::sync-input-values panel})))

(defn update-url-params [state defaults]
  (let [{:keys [panel]} (current-state @state defaults)
        root-state (panel-cursor panel)]
    (nav-list (current-state @root-state defaults))))

(reg-event-fx
 ::sync-url-params
 [trim-v]
 (fn [{:keys [db]} [panel]]
   (nav-list (current-state (get-in db (panel-state-path panel))
                            (panel-defaults panel))
             :redirect? true)
   {}))

(defn sync-url-params [panel]
  (dispatch [::set-recent-nav-action panel nil])
  (js/setTimeout #(dispatch [::sync-url-params panel])
                 50))

(def group-statuses
  [:single :determined :conflict :consistent :resolved])

(defn- confirm-status-filter [status]
  (if (nil? status)
    (constantly true)
    #(= (:confirmed %) status)))

(defn- group-status-filter [status]
  (case status
    :conflict is-conflict?
    :resolved is-resolved?
    :consistent is-consistent?
    :single is-single?
    :determined (some-fn is-consistent? is-resolved?)
    (constantly true)))

(defn- inclusion-status-filter [status group-status]
  (if (nil? status)
    (constantly true)
    (fn [entries]
      (let [entries (if (or (= group-status :resolved)
                            (and (= group-status :determined)
                                 (is-resolved? entries)))
                      (filter :resolve entries)
                      entries)
            inclusion (->> entries (map :inclusion) distinct)]
        (in? inclusion status)))))

(defn- answer-value-filter [value group-status]
  (if (nil? value)
    (constantly true)
    (fn [entries]
      (let [entries (if (or (= group-status :resolved)
                            (and (= group-status :determined)
                                 (is-resolved? entries)))
                      (filter :resolve entries)
                      entries)
            answers (->> entries
                         (map :answer)
                         (map #(if (sequential? %) % [%]))
                         (apply concat)
                         distinct)]
        (in? answers value)))))

(defn- label-user-filter [label-user]
  (if (nil? label-user)
    (constantly true)
    (fn [labels]
      (->> labels
           (filter #(= (:user-id %) label-user))
           not-empty))))

(defn query-args [{:keys [options filters offset]}]
  (let [{:keys [display-count]} options]
    (merge (apply merge-with vector filters)
           {:n-offset offset
            :n-count display-count})))

(defn list-data-query [cstate]
  (let [project-id @(subscribe [:active-project-id])
        args (query-args cstate)]
    [:project/article-list project-id args]))

(defn list-count-query [cstate]
  (let [project-id @(subscribe [:active-project-id])
        args (dissoc (query-args cstate)
                     :n-count :n-offset)]
    [:project/article-list-count project-id args]))

(defn- reload-list-data [cstate]
  (dispatch [:require (list-data-query cstate)])
  (dispatch [:reload (list-data-query cstate)]))

(defn- reload-list-count [cstate]
  (dispatch [:require (list-count-query cstate)])
  (dispatch [:reload (list-count-query cstate)]))

(defn- reload-list [cstate & [nav-action]]
  (when nav-action
    (dispatch [::set-recent-nav-action (:panel cstate) nav-action]))
  (reload-list-count cstate)
  (reload-list-data cstate))

(defn visible-articles [cstate]
  @(subscribe (list-data-query cstate)))

(defn total-articles-count [cstate]
  @(subscribe (list-count-query cstate)))

(defn current-state-ready? [cstate]
  (let [item1 (list-data-query cstate)
        item2 (list-count-query cstate)]
    (and @(subscribe [:have? item1])
         @(subscribe [:have? item2]))))

(reg-event-db
 :article-list/update-ready-state
 [trim-v]
 (fn [db [panel]]
   (let [path (panel-state-path panel)
         stateval (get-in db path)]
     (assoc-in db (concat path [:ready])
               (dissoc stateval :ready)))))

(defn update-ready-state [panel]
  (let [state (panel-cursor panel)
        defaults (panel-defaults panel)
        cstate (current-state @state defaults)]
    (when (current-state-ready? cstate)
      (dispatch [:article-list/update-ready-state panel]))))

(reg-event-db
 ::set-recent-nav-action
 [trim-v]
 (fn [db [panel action]]
   (let [path (panel-state-path panel)
         have-ready? (contains? (get-in db path) :ready)]
     (cond-> (assoc-in db (concat path [:recent-nav-action])
                       action)
       have-ready? (assoc-in (concat path [:ready :recent-nav-action])
                             action)))))

(reg-event-db
 :article-list/toggle-display-option
 [trim-v]
 (fn [db [panel key value]]
   (let [path (panel-state-path panel)]
     (assoc-in db (concat path [:display key]) value))))

(reg-event-db
 ::set-display-offset
 [trim-v]
 (fn [db [panel offset]]
   (let [path (panel-state-path panel)]
     (assoc-in db (concat path [:offset]) offset))))

(defn ensure-data-loaded [panel]
  (let [state (panel-cursor panel)
        defaults (panel-defaults panel)
        cstate (current-state @state defaults)]
    (dispatch [:require (list-data-query cstate)])
    (dispatch [:require (list-count-query cstate)])))

(defn wrap-change-filters [state defaults f & {:keys [reload?]}]
  (let [{:keys [panel] :as cstate} (current-state @state defaults)]
    (ensure-data-loaded panel)
    (if (current-state-ready? cstate)
      (do (f)
          (update-url-params state defaults)
          (when reload?
            (reload-list cstate)))
      (do nil))))

(defn set-display-offset [state defaults offset]
  (let [{:keys [panel active-article] :as cstate}
        (current-state @state defaults)
        new-cstate (assoc cstate :offset offset)
        change-offset
        (fn []
          (wrap-change-filters
           state defaults
           (fn []
             (dispatch-sync [::set-display-offset panel offset])
             (when @(subscribe [:have? (list-data-query new-cstate)])
               (dispatch [:reload (list-data-query new-cstate)])))
           :reload? false))]
    (if active-article
      (do (nav-list cstate :redirect? true)
          (js/setTimeout change-offset 25))
      (change-offset))))

(defn- max-display-offset [cstate]
  (let [total-count (total-articles-count cstate)
        {:keys [display-count]} (:options cstate)]
    (* display-count (quot (dec total-count) display-count))))

(defn- next-article-id [cstate]
  (let [articles (visible-articles cstate)
        visible-ids (map :article-id articles)
        {:keys [active-article]} cstate]
    (when (in? visible-ids active-article)
      (->> visible-ids
           (drop-while #(not= % active-article))
           (drop 1)
           first))))

(defn- prev-article-id [cstate]
  (let [articles (visible-articles cstate)
        visible-ids (map :article-id articles)
        {:keys [active-article]} cstate]
    (when (in? visible-ids active-article)
      (->> visible-ids
           (take-while #(not= % active-article))
           last))))

(defn- private-article-view? [cstate]
  ;; TODO: function
  nil)

(defn- resolving-allowed? [cstate]
  (boolean
   (let [{:keys [active-article panel]} cstate]
     (when active-article
       (and (not (private-article-view? panel))
            (= :conflict @(subscribe [:article/review-status active-article]))
            @(subscribe [:member/resolver?]))))))

(defn- editing-allowed? [cstate]
  (boolean
   (let [{:keys [active-article panel]} cstate]
     (when active-article
       (or (resolving-allowed? cstate)
           (in? [:confirmed :unconfirmed]
                @(subscribe [:article/user-status active-article])))))))

(defn- editing-article? [cstate]
  (boolean
   (let [{:keys [active-article panel]} cstate]
     (when active-article
       (and (editing-allowed? cstate)
            (or (and (private-article-view? cstate)
                     (= @(subscribe [:article/user-status active-article])
                        :unconfirmed))
                @(subscribe [:review/change-labels? active-article panel])))))))

(defn- resolving-article? [cstate]
  (boolean
   (and (editing-article? cstate)
        (resolving-allowed? cstate))))

(defn input-value-cursor [state input-key]
  (r/cursor state [:inputs input-key]))

(defn filters-cursor [state]
  (r/cursor state [:filters]))

(defn current-filters [state defaults]
  (:filters (current-state @state defaults)))

(defn filter-entries [state defaults key]
  (->> (current-filters state defaults)
       (filterv #(in? (keys %) key))))

(defn replace-filter-entry [state defaults entry-old entry-new]
  (->> (current-filters state defaults)
       (mapv #(if (= % entry-old) entry-new %))
       distinct vec))

(defn sync-input-values [state defaults]
  (let [{:keys [filters] :as cstate}
        (current-state @state defaults)
        text-search (->> filters
                         (filter #(in? (keys %) :text-search))
                         first vals first)
        text-search-input (input-value-cursor state :text-search)]
    (reset! text-search-input text-search)))

(reg-fx
 ::sync-input-values
 (fn [panel]
   (let [state (panel-cursor panel)
         defaults (panel-defaults panel)]
     (sync-input-values state defaults))))

(defn replace-filter-key [state defaults key new-value]
  (let [filters (current-filters state defaults)]
    (if (some #(= % key)
              (->> filters (map keys) (apply concat)))
      (->> filters
           (mapv #(if (in? (keys %) key)
                    {key new-value} %))
           (filterv #(not (every? nil? (vals %))))
           distinct vec)
      (conj filters {key new-value}))))

(defn reset-filters [state defaults]
  (let [default-defaults (default-defaults)]
    (set-display-offset state defaults 0)
    (swap! state assoc :filters
           (or (:filters defaults)
               (:filters default-defaults)))
    (sync-url-params (or (:panel @state)
                         (:panel defaults)
                         (:panel default-defaults)))
    (sync-input-values state defaults)))

(defn- TextSearchInput [state defaults]
  (let [key :text-search
        {:keys [panel] :as cstate} (current-state @state defaults)
        input (input-value-cursor state key)
        [entry] (filter-entries state defaults key)
        curval (get entry key)
        synced? (= @input curval)]
    [:div.ui.fluid.icon.input
     {:class (when-not synced? "loading")}
     [:input
      {:type "text" :id "article-search" :name "article-search"
       :value (or @input curval)
       :on-change
       (fn [event]
         (let [value (-> event .-target .-value)]
           (reset! input value)
           (js/setTimeout
            #(let [later-value @input]
               (let [later-value
                     (if (empty? later-value) nil later-value)
                     value
                     (if (empty? value) nil value)]
                 (when (= value later-value)
                   (reset! (filters-cursor state)
                           (replace-filter-key
                            state defaults key value))
                   (when (empty? value)
                     (reset! input nil))
                   (sync-url-params panel))))
            750)))}]
     [:i.search.icon]]))

(defn make-filter-descriptions [state defaults]
  (let [{:keys [filters] :as cstate}
        (current-state @state defaults)
        descriptions
        (->> filters
             (map
              (fn [m]
                (let [key (-> m keys first)
                      value (-> m vals first)]
                  (case key
                    :label-id
                    (when value
                      (str "has label "
                           (pr-str @(subscribe [:label/display value]))))

                    :text-search
                    (when (and (string? value)
                               (not-empty value))
                      (str "text contains " (pr-str value)))

                    nil))))
             (remove nil?))]
    (if (empty? descriptions)
      ["all articles"]
      descriptions)))

(defn- ArticleListFilters [state defaults]
  (let [cstate (current-state @state defaults)
        {:keys [recent-nav-action panel display]} cstate
        {:keys [expand-filters show-inclusion
                show-labels show-notes]} display
        project-id @(subscribe [:active-project-id])
        loading? (and (loading/any-loading? :only :project/article-list)
                      @(loading/loading-indicator))
        view-button
        (fn [option-key label]
          (let [status (get display option-key)]
            [:button.ui.small.labeled.icon.button
             {:on-click
              #(dispatch [:article-list/toggle-display-option
                          panel option-key (not status)])}
             (if status
               [:i.green.circle.icon]
               [:i.grey.circle.icon])
             label]))]
    [:div.ui.segments.article-filters
     [:div.ui.secondary.middle.aligned.grid.segment.filters-minimal
      [:div.row
       [:div.one.wide.column.medium-weight.filters-header
        "Filters"]
       [:div.nine.wide.column.filters-summary
        [:span
         (doall
          (map-indexed
           (fn [i s] ^{:key [:filter-text i]}
             [:div.ui.label s])
           (make-filter-descriptions state defaults)))]]
       [:div.six.wide.column.right.aligned.control-buttons
        [:button.ui.small.icon.button
         {:class (if (and loading? (= recent-nav-action :refresh))
                   "loading" "")
          :on-click #(reload-list cstate :refresh)}
         [:i.repeat.icon]]
        [:button.ui.small.icon.button
         {:on-click #(reset-filters state defaults)}
         [:i.erase.icon]]]]]
     [:div.ui.secondary.segment.no-padding
      [:button.ui.tiny.fluid.icon.button
       {:style (cond-> {:border-top-left-radius "0"
                        :border-top-right-radius "0"}
                 expand-filters
                 (merge {:border-bottom-left-radius "0"
                         :border-bottom-right-radius "0"}))
        :on-click #(dispatch [:article-list/toggle-display-option
                              panel :expand-filters (not expand-filters)])}
       (if expand-filters
         [:i.chevron.up.icon]
         [:i.chevron.down.icon])]]
     (when expand-filters
       [:div.ui.secondary.segment.filters-content
        [:div.ui.small.form
         [:div.field
          [:div.fields
           [:div.four.wide.field
            [:label "Text search"]
            [TextSearchInput state defaults]]
           [:div.six.wide.field
            [:label "View Options"]
            [view-button :show-inclusion "Inclusion"]
            [view-button :show-labels "Labels"]
            [view-button :show-notes "Notes"]]
           [:div.six.wide.field]]]]])]))

(defn- ArticleListNavHeader [state defaults]
  (let [{:keys [panel] :as visible-cstate} (current-state @state defaults)
        {:keys [display-count]} (:options visible-cstate)
        root-state (panel-cursor panel)
        root-cstate (current-state @root-state defaults)]
    [:div.ui.segment.article-nav
     [ListPager
      {:panel panel
       :instance-key [:article-list]
       :offset (:offset root-cstate)
       :total-count (total-articles-count root-cstate)
       :items-per-page display-count
       :item-name-string "articles"
       :set-offset #(set-display-offset root-state defaults %)
       :on-nav-action
       (fn [action offset]
         (dispatch-sync [::set-recent-nav-action panel action]))
       :recent-nav-action nil #_ (:recent-nav-action root-cstate)
       :loading? (loading/any-loading? :only :project/article-list)
       :message-overrides
       {:offset (:offset visible-cstate)
        :total-count (total-articles-count visible-cstate)}}]]))

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

(defn- ArticleContent [state defaults article-id]
  (let [cstate (current-state @state defaults)
        {:keys [panel]} cstate
        editing-allowed? (editing-allowed? cstate)
        resolving-allowed? (resolving-allowed? cstate)
        editing? (editing-article? cstate)]
    [:div
     [article-info-view article-id
      :show-labels? true
      :private-view? (private-article-view? cstate)
      :context :article-list]
     (cond editing?
           [label-editor-view article-id]

           editing-allowed?
           [:div.ui.segment
            [:div.ui.fluid.button
             {:on-click
              #(dispatch [:review/enable-change-labels article-id panel])}
             (if resolving-allowed? "Resolve Labels" "Change Labels")]])]))

(defn- ArticleListEntry [state defaults article full-size?]
  (let [cstate (current-state @state defaults)
        {:keys [active-article]} cstate
        overall-id @(subscribe [:project/overall-label-id])
        {:keys [article-id primary-title labels updated-time]} article
        active? (and active-article (= article-id active-article))
        overall-labels (->> labels (filter #(= (:label-id %) overall-id)))
        answer-class
        (cond
          (is-resolved? overall-labels) "resolved"
          (is-consistent? overall-labels) "consistent"
          (is-single? overall-labels) "single"
          :else "conflict")]
    (if full-size?
      ;; non-mobile view
      [:div.ui.row
       [:div.ui.thirteen.wide.column.article-title
        [:div.ui.middle.aligned.grid
         [:div.row
          [:div.ui.one.wide.center.aligned.column
           [:div.ui.fluid.labeled.center.aligned.button
            [:i.fitted.center.aligned
             {:class (str (if active? "down" "right")
                          " chevron icon")
              :style {:width "100%"}}]]]
          [:div.thirteen.wide.column>span.article-title primary-title]
          [:div.two.wide.center.aligned.column.article-updated-time
           (when-let [updated-time (some-> updated-time (time-from-epoch))]
             [updated-time-label updated-time])]]]]
       [:div.ui.three.wide.center.aligned.middle.aligned.column.article-answers
        (when (not-empty labels)
          {:class answer-class})
        (when (not-empty labels)
          [:div.ui.middle.aligned.grid>div.row>div.column
           [AnswerCell article-id overall-labels answer-class]])]]
      ;; mobile view
      [:div.ui.row
       [:div.ui.ten.wide.column.article-title
        [:span.article-title primary-title]
        (when-let [updated-time (some-> updated-time (time-from-epoch))]
          [updated-time-label updated-time])]
       [:div.ui.six.wide.center.aligned.middle.aligned.column.article-answers
        (when (not-empty labels)
          {:class answer-class})
        (when (not-empty labels)
          [:div.ui.middle.aligned.grid>div.row>div.column
           [AnswerCell article-id overall-labels answer-class]])]])))

(defn- ArticleListContent [state defaults]
  (let [cstate (current-state @state defaults)
        {:keys [recent-article active-article]} cstate
        project-id @(subscribe [:active-project-id])
        articles (visible-articles cstate)
        full-size? (full-size?)]
    [:div.ui.segments.article-list-segments
     (doall
      (concat
       (list
        ^{:key :article-nav-header}
        [ArticleListNavHeader state defaults])
       (->>
        articles
        (map
         (fn [{:keys [article-id] :as article}]
           (let [recent? (= article-id recent-article)
                 active? (= article-id active-article)
                 have? @(subscribe [:have? [:article project-id article-id]])
                 classes (if (or active? recent?) "active" "")
                 loading? (loading/item-loading? [:article project-id article-id])
                 next-id (next-article-id cstate)
                 prev-id (prev-article-id cstate)
                 next-url
                 (when next-id
                   (nav/make-url (article-uri cstate next-id)
                                 (make-url-params cstate)))
                 prev-url
                 (when prev-id
                   (nav/make-url (article-uri cstate prev-id)
                                 (make-url-params cstate)))]
             (doall
              (list
               [:a.ui.middle.aligned.grid.segment.article-list-article
                {:key [:list-row article-id]
                 :class (str (if recent? "active" ""))
                 :href (if active?
                         (nav/make-url (panel-base-uri cstate)
                                       (make-url-params cstate))
                         (nav/make-url (article-uri cstate article-id)
                                       (make-url-params cstate)))}
                [ArticleListEntry state defaults article full-size?]]
               (when (= article-id active-article)
                 (doall
                  (list
                   [:div.ui.middle.aligned.segment.no-padding.article-list-article-nav
                    {:key [:article-nav article-id]}
                    [:div.ui.fluid.buttons
                     [:a.ui.center.aligned.icon.button
                      {:href prev-url
                       :class (if prev-url nil "disabled")}
                      [:i.left.chevron.icon]]
                     [:a.ui.center.aligned.icon.button
                      {:href next-url
                       :class (if next-url nil "disabled")}
                      [:i.right.chevron.icon]]]]
                   [:div.ui.middle.aligned.grid.segment.article-list-full-article
                    {:key [:article-row article-id]
                     :class (str (if recent? "active" "")
                                 " "
                                 (if loading? "article-loading" ""))}
                    (when (and loading? (not have?))
                      [:div.ui.active.inverted.dimmer
                       [:div.ui.loader]])
                    [ArticleContent state defaults article-id]])))))))))))]))

(defn ArticleListExpandedEntry [state defaults cstate article]
  (let [{:keys [article-id]} article
        cstate (current-state @state defaults)
        {:keys [recent-article active-article]} cstate
        project-id @(subscribe [:active-project-id])
        recent? (= article-id recent-article)
        active? (= article-id active-article)
        have? @(subscribe [:have? [:article project-id article-id]])
        classes (if (or active? recent?) "active" "")
        loading? (loading/item-loading? [:article project-id article-id])]
    (doall
     (list
      [:a.ui.middle.aligned.grid.segment.article-list-article
       {:key [:list-row article-id]
        :class (str (if recent? "active" ""))
        :href (if active?
                (panel-base-uri cstate)
                (article-uri cstate article-id))}
       [ArticleListEntry state defaults article full-size?]]
      (when (= article-id active-article)
        [:div.ui.middle.aligned.grid.segment.article-list-full-article
         {:key [:article-row article-id]
          :class (str (if recent? "active" "")
                      " "
                      (if loading? "article-loading" ""))}
         (when (and loading? (not have?))
           [:div.ui.active.inverted.dimmer
            [:div.ui.loader]])
         [ArticleContent state defaults article-id]])))))

(defn SingleArticlePanel [state defaults]
  (let [cstate (current-state @state defaults)
        {:keys [active-article]} cstate
        project-id @(subscribe [:active-project-id])
        title @(subscribe [:article/title active-article])]
    (with-loader [[:article project-id active-article]] {}
      [:div>div.article-list-view
       [:div.ui.segments.article-list-segments
        (ArticleListExpandedEntry
         state defaults cstate
         {:article-id active-article
          :primary-title title})]])))

(defn MultiArticlePanel [state defaults]
  (let [cstate (current-state @state defaults)
        {:keys [panel]} cstate
        articles (visible-articles cstate)
        item1 (list-count-query cstate)
        item2 (list-data-query cstate)
        ready? (and @(subscribe [:have? item1])
                    @(subscribe [:have? item2])
                    (not (loading/item-loading? item1))
                    (not (loading/item-loading? item2)))]
    (if (and (not ready?) (contains? @state :ready))
      [:div.article-list-view
       [ArticleListFilters state defaults]
       [ArticleListContent (r/cursor state [:ready]) defaults] ]
      (with-loader [item1 item2] {}
        [:div.article-list-view
         (when (not= @(r/cursor state [:ready])
                     (-> @state (dissoc :ready)))
           (update-ready-state panel))
         [ArticleListFilters state defaults]
         [ArticleListContent state defaults]]))))

(defn ArticleListPanel [state defaults]
  (let [cstate (current-state @state defaults)
        articles (visible-articles cstate)
        {:keys [active-article]} cstate
        visible-ids (map :article-id articles)
        item (list-data-query cstate)]
    (when item
      (dispatch [:require item])
      (dispatch [:require (list-count-query cstate)]))
    [:div.article-list-toplevel-new
     (if (and active-article (not (in? visible-ids active-article)))
       [SingleArticlePanel state defaults]
       [MultiArticlePanel state defaults])]))

(when use-new-article-list?
  (reg-sub
   :article-list/panel-state
   (fn [db [_ panel]]
     (get-in db [:state :panels panel])))

  ;; Gets id of article currently being individually displayed
  (reg-sub
   :article-list/article-id
   (fn [[_ panel]]
     [(subscribe [:active-panel])
      (subscribe [:panel-field [:article-list :active-article] panel])])
   (fn [[active-panel article-id] [_ panel]]
     (when (= active-panel panel)
       article-id)))

  (reg-sub-raw
   :article-list/editing?
   (fn [_ [_ panel]]
     (reaction
      (editing-article?
       (current-state @(panel-cursor panel)
                      (panel-defaults panel))))))

  (reg-sub-raw
   :article-list/resolving?
   (fn [_ [_ panel]]
     (reaction
      (resolving-article?
       (current-state @(panel-cursor panel)
                      (panel-defaults panel))))))

  (reg-event-fx
   :article-list/set-recent-article
   [trim-v]
   (fn [{:keys [db]} [panel article-id]]
     (let [path (panel-state-path panel)]
       {:db (assoc-in db (concat path [:recent-article]) article-id)})))

  (reg-event-fx
   :article-list/set-active-article
   [trim-v]
   (fn [{:keys [db]} [panel article-id]]
     (let [path (panel-state-path panel)]
       (cond-> {:db (assoc-in db (concat path [:active-article]) article-id)}
         article-id
         (merge {:dispatch [:article-list/set-recent-article panel article-id]}))))))
