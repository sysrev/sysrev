(ns sysrev.views.article-list
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.article :refer [article-info-view]]
            [sysrev.views.review :refer [label-editor-view]]
            [sysrev.views.components :refer
             [with-ui-help-tooltip ui-help-icon selection-dropdown
              three-state-selection-icons updated-time-label]]
            [sysrev.nav :refer [nav]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.shared.keywords :refer [canonical-keyword]]
            [sysrev.shared.article-list :refer
             [is-resolved? resolved-answer is-conflict? is-single? is-consistent?]]
            [sysrev.util :refer [full-size? mobile? nbsp time-from-epoch]]
            [sysrev.shared.util :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmulti panel-defaults identity)
(defmethod panel-defaults :default [] nil)

(defn default-defaults []
  (let [overall-id @(subscribe [:project/overall-label-id])]
    {:options
     {:display-count (if (mobile?) 10 20)}
     :filters
     {:label-id overall-id}
     :display-offset 0
     :base-uri (fn [project-id]
                 (project-uri project-id "/articles"))}))

(defn panel-cursor [panel]
  (r/cursor app-db [:state :panels panel :article-list]))

(defn current-state [state defaults]
  (merge-with (fn [a b]
                (if (and (or (nil? a) (map? a))
                         (or (nil? b) (map? b)))
                  (merge a b)
                  b))
              (default-defaults) defaults @state))

(defn- panel-base-uri [cstate]
  ((:base-uri cstate)
   @(subscribe [:active-project-id])))

(defn- article-uri [cstate article-id]
  (str (panel-base-uri cstate) "/" article-id))

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

(defn query-args [{:keys [options filters display-offset]}]
  (let [{:keys [display-count]} options]
    (merge filters
           {:n-offset display-offset
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
  (dispatch [:reload (list-data-query cstate)]))

(defn- reload-list-count [cstate]
  (dispatch [:reload (list-count-query cstate)]))

(defn visible-articles [cstate]
  @(subscribe (list-data-query cstate)))

(defn total-articles-count [cstate]
  @(subscribe (list-count-query cstate)))

(defn set-recent-article [state article-id]
  (swap! state assoc :recent-article article-id))

(defn get-active-article [state]
  (:active-article @state))

(defn set-active-article [state article-id]
  (when article-id
    (set-recent-article state article-id))
  (swap! state assoc :active-article article-id))

(defn set-display-offset [state offset]
  (swap! state assoc :display-offset offset))

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

(defn reset-filters [state]
  (set-display-offset state 0)
  (swap! state assoc :filters nil))

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

(defn- toggle-change-labels [state enable?]
  ;; TODO: function
  (swap! state assoc :change-labels? enable?))

(defn- loading-articles? [state defaults]
  ;; TODO: function
  nil)

(defn- input-value-cursor [state input-key]
  (r/cursor state [:inputs input-key]))

(defn- filter-value-cursor [state filter-key]
  (r/cursor state [:filters filter-key]))

(defn- TextSearchInput [state defaults]
  (let [input (input-value-cursor state :text-search)
        filter-cursor (filter-value-cursor state :text-search)
        curval (get-in @state [:filters :text-search])
        synced? (= @input curval)]
    [:div.ui.fluid.icon.input
     {:class (when-not synced? "loading")}
     [:input {:type "text" :id "article-search" :name "article-search"
              :value @input
              :on-change
              (fn [event]
                (let [value (-> event .-target .-value)]
                  (reset! input value)
                  (js/setTimeout
                   #(let [later-value @input]
                      (when (= value later-value)
                        (reset! filter-cursor value)))
                   500)))}]
     [:i.search.icon]]))

(defn- ArticleListFilters [state defaults]
  (let [cstate (current-state state defaults)
        project-id @(subscribe [:active-project-id])

        refresh-button
        [:button.ui.right.labeled.icon.button.refresh-button
         {:class (if (loading-articles? state defaults) "loading" "")
          :style {:width "100%"}
          :on-click #(reload-list-data cstate)}
         [:i.repeat.icon]
         (if (full-size?) "Refresh" "Refresh")]]
    [:div.article-filters
     [:div.ui.secondary.top.attached.segment
      {:style {:padding "10px"}}
      [:div.ui.two.column.grid
       [:div.middle.aligned.row
        {:style {:padding-top "9px"
                 :padding-bottom "9px"}}
        [:div.left.aligned.column
         [:h4 "Filters"]]
        [:div.right.aligned.column
         [:button.ui.tiny.right.labeled.icon.button
          [:i.erase.icon]
          "Reset"]]]]]
     [:div.ui.secondary.attached.segment.filters-content
      {:style {:padding "10px"}}
      [:div.ui.small.form
       [:div.field
        [:div.fields
         [:div.four.wide.field
          [:label "Text search"]
          [TextSearchInput state defaults]]
         [:div.five.wide.field
          [:label "View Mode"]
          [:div {:style { ;; :text-align "center"
                         :width "100%"}}
           [:button.ui.labeled.icon.button
            [:i.green.circle.icon]
            "Self"]
           [:button.ui.labeled.icon.button
            [:i.grey.circle.icon]
            "Consensus"]]]
         [:div.five.wide.field
          [:label "View Options"]
          [:div {:style { ;; :text-align "center"
                         :width "100%"}}
           [:button.ui.labeled.icon.button
            [:i.green.circle.icon]
            "Labels"]
           [:button.ui.labeled.icon.button
            [:i.green.circle.icon]
            "Notes"]]]
         [:div.two.wide.field [:label nbsp] refresh-button]]]]]
     [:div.ui.secondary.bottom.attached.segment
      {:style {:padding "0px"}}
      [:button.ui.tiny.fluid.icon.button
       {:style {:border-top-left-radius "0"
                :border-top-right-radius "0"
                :padding-top "7px"
                :padding-bottom "7px"}}
       [:i.chevron.up.icon]]]]))

(defn- ArticleListNavMessage [cstate]
  (let [{:keys [display-offset options]} cstate
        {:keys [display-count]} options
        articles (visible-articles cstate)
        total-count (total-articles-count cstate)]
    [:h5.no-margin
     (if (or (nil? total-count) (zero? total-count))
       "No matching articles found"
       (str "Showing "
            (+ display-offset 1)
            "-"
            (+ display-offset
               (if (pos? (count articles))
                 (count articles)
                 display-count))
            " of "
            total-count
            " matching articles "))]))

(defn- ArticleListNavButtons [state defaults]
  (let [cstate (current-state state defaults)
        {:keys [options display-offset]} cstate
        {:keys [display-count]} options
        total-count (total-articles-count cstate)
        max-offset (max-display-offset cstate)
        on-first #(set-display-offset state 0)
        on-last #(set-display-offset state max-offset)
        on-next
        #(when (< (+ display-offset display-count) total-count)
           (set-display-offset
            state (+ display-offset display-count)))
        on-previous
        #(when (>= display-offset display-count)
           (set-display-offset
            state (max 0 (- display-offset display-count))))]
    (if (full-size?)
      ;; non-mobile view
      [:div.ui.right.aligned.column
       [:div.ui.tiny.icon.button
        {:class (if (= display-offset 0) "disabled" "")
         :style {:margin-right "5px"}
         :on-click on-first}
        [:i.angle.double.left.icon]]
       [:div.ui.tiny.buttons
        {:style {:margin-right "5px"}}
        [:div.ui.tiny.button
         {:class (if (= display-offset 0) "disabled" "")
          :on-click on-previous}
         [:i.chevron.left.icon] "Previous"]
        [:div.ui.tiny.button
         {:class (if (>= (+ display-offset display-count) total-count)
                   "disabled" "")
          :on-click on-next}
         "Next" [:i.chevron.right.icon]]]
       [:div.ui.tiny.icon.button
        {:class (if (>= (+ display-offset display-count) total-count)
                  "disabled" "")
         :style {:margin-right "0"}
         :on-click on-last}
        [:i.angle.double.right.icon]]]
      ;; mobile view
      [:div.ui.right.aligned.nine.wide.column
       [:div.ui.tiny.icon.button
        {:class (if (= display-offset 0) "disabled" "")
         :style {:margin-right "4px"}
         :on-click on-first}
        [:i.angle.double.left.icon]]
       [:div.ui.tiny.buttons
        {:style {:margin-right "4px"}}
        [:div.ui.tiny.button
         {:class (if (= display-offset 0) "disabled" "")
          :on-click on-previous}
         [:i.chevron.left.icon] "Previous"]
        [:div.ui.tiny.button
         {:class (if (>= (+ display-offset display-count) total-count)
                   "disabled" "")
          :on-click on-next}
         "Next" [:i.chevron.right.icon]]]
       [:div.ui.tiny.icon.button
        {:class (if (>= (+ display-offset display-count) total-count)
                  "disabled" "")
         :on-click on-last}
        [:i.angle.double.right.icon]]])))

(defn- ArticleListNavHeader [state defaults]
  (let [cstate (current-state state defaults)]
    [:div.ui.segment.article-nav
     (if (full-size?)
       [:div.ui.two.column.middle.aligned.grid
        [:div.ui.left.aligned.column
         [ArticleListNavMessage cstate]]
        [ArticleListNavButtons state defaults]]
       [:div.ui.middle.aligned.grid
        [:div.ui.left.aligned.seven.wide.column
         [ArticleListNavMessage cstate]]
        [ArticleListNavButtons state defaults]])]))

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
  (let [cstate (current-state state defaults)
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
             {:on-click #(toggle-change-labels state true)}
             (if resolving-allowed? "Resolve Labels" "Change Labels")]])]))

(defn- ArticleListEntry [state defaults article full-size?]
  (let [cstate (current-state state defaults)
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
  (let [cstate (current-state state defaults)
        {:keys [recent-article active-article]} cstate
        project-id @(subscribe [:active-project-id])
        articles (visible-articles cstate)
        show-article #(nav (article-uri cstate %))
        full-size? (full-size?)]
    [:div.ui.segments.article-list-segments
     (doall
      (->>
       articles
       (map
        (fn [{:keys [article-id] :as article}]
          (let [recent? (= article-id recent-article)
                active? (= article-id active-article)
                have? @(subscribe [:have? [:article project-id article-id]])
                classes (if (or active? recent?) "active" "")
                loading? @(subscribe [:loading? [:article project-id article-id]])]
            (doall
             (list
              [:a.ui.middle.aligned.attached.grid.segment.article-list-article
               {:key [:list-row article-id]
                :class (str (if recent? "active" "")
                            " "
                            #_ (if loading? "article-loading" ""))
                ;; :on-click #(show-article article-id)
                :href (if active?
                        (panel-base-uri cstate)
                        (article-uri cstate article-id))}
               #_ (when loading?
                    [:div.ui.active.inverted.dimmer
                     [:div.ui.loader]])
               [ArticleListEntry state defaults article full-size?]]
              (when (= article-id active-article)
                [:div.ui.middle.aligned.attached.grid.segment.article-list-full-article
                 {:key [:article-row article-id]
                  :class (str (if recent? "active" "")
                              " "
                              (if loading? "article-loading" ""))}
                 (when (and loading? (not have?))
                   [:div.ui.active.inverted.dimmer
                    [:div.ui.loader]])
                 [ArticleContent state defaults article-id]]))))))))]))

(defn ArticleListExpandedEntry [state defaults cstate article]
  (let [{:keys [article-id]} article
        cstate (current-state state defaults)
        {:keys [recent-article active-article]} cstate
        project-id @(subscribe [:active-project-id])
        recent? (= article-id recent-article)
        active? (= article-id active-article)
        have? @(subscribe [:have? [:article project-id article-id]])
        classes (if (or active? recent?) "active" "")
        loading? @(subscribe [:loading? [:article project-id article-id]])]
    (doall
     (list
      [:a.ui.middle.aligned.attached.grid.segment.article-list-article
       {:key [:list-row article-id]
        :class (str (if recent? "active" "")
                    " "
                    #_ (if loading? "article-loading" ""))
        :href (if active?
                (panel-base-uri cstate)
                (article-uri cstate article-id))}
       [ArticleListEntry state defaults article full-size?]]
      (when (= article-id active-article)
        [:div.ui.middle.aligned.attached.grid.segment.article-list-full-article
         {:key [:article-row article-id]
          :class (str (if recent? "active" "")
                      " "
                      (if loading? "article-loading" ""))}
         (when (and loading? (not have?))
           [:div.ui.active.inverted.dimmer
            [:div.ui.loader]])
         [ArticleContent state defaults article-id]])))))

(defn SingleArticlePanel [state defaults active-article]
  (let [cstate (current-state state defaults)
        project-id @(subscribe [:active-project-id])
        title @(subscribe [:article/title active-article])]
    (with-loader [[:article project-id active-article]] {}
      [:div>div.article-list-view
       [:div.ui.segments.article-list-segments
        (ArticleListExpandedEntry
         state defaults cstate
         {:article-id active-article
          :primary-title title})]])))

(defn MultiArticlePanel [state defaults articles active-article]
  (let [cstate (current-state state defaults)]
    [:div
     [ArticleListFilters state defaults]
     [:div.article-list-view
      [ArticleListNavHeader state defaults]
      (with-loader [(list-data-query cstate)] {}
        (when (not-empty articles)
          [ArticleListContent state defaults]))]]))

(defn ArticleListPanel [state defaults]
  (let [cstate (current-state state defaults)
        articles (visible-articles cstate)
        {:keys [active-article]} cstate
        visible-ids (map :article-id articles)
        item (list-data-query cstate)]
    (when item (dispatch [:require item]))
    (if (and active-article (not (in? visible-ids active-article)))
      [SingleArticlePanel state defaults active-article]
      [MultiArticlePanel state defaults articles active-article])))

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
     (current-state (panel-cursor panel)
                    (panel-defaults panel))))))

(reg-sub-raw
 :article-list/resolving?
 (fn [_ [_ panel]]
   (reaction
    (resolving-article?
     (current-state (panel-cursor panel)
                    (panel-defaults panel))))))
