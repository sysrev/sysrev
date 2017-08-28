  (ns sysrev.views.article-list
   (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [re-frame.core :as re-frame :refer
     [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
      reg-event-db reg-event-fx reg-fx trim-v]]
    [reagent.ratom :refer [reaction]]
    [sysrev.views.article :refer [article-info-view]]
    [sysrev.views.review :refer [label-editor-view]]
    [sysrev.views.components :refer
     [with-ui-help-tooltip ui-help-icon selection-dropdown three-state-selection-icons]]
    [sysrev.subs.ui :refer [get-panel-field]]
    [sysrev.routes :refer [nav]]
    [sysrev.shared.keywords :refer [canonical-keyword]]
    [sysrev.util :refer [full-size? mobile? nbsp]]
    [sysrev.shared.util :refer [in? map-values]])
   (:require-macros [sysrev.macros :refer [with-loader]]))

(defmulti default-filters-sub (fn [panel] panel))
(defmulti panel-base-uri (fn [panel] panel))
(defmulti article-uri (fn [panel _] panel))
(defmulti all-articles-sub (fn [panel] panel))
(defmulti allow-null-label? (fn [panel] panel))
(defmulti list-header-tooltip (fn [panel] panel))
(defmulti render-article-entry (fn [panel article full-size?] panel))
(defmulti private-article-view? (fn [panel] panel))
(defmulti loading-articles? (fn [panel user-id] panel))
(defmulti reload-articles (fn [panel user-id] panel))
(defmulti auto-refresh? (fn [panel] panel))

(defmethod list-header-tooltip :default [] nil)

(def ^:private display-count 10)

(def group-statuses
  [#_ :single :consistent :conflict :resolved])

(defn is-resolved? [labels]
  (boolean (some :resolve labels)))
(defn resolved-answer [labels]
  (->> labels (filter :resolve) first))
(defn is-conflict? [labels]
  (and (not (is-resolved? labels))
       (< 1 (count (->> labels (map :inclusion) distinct)))))
(defn is-single? [labels]
  (= 1 (count labels)))
(defn is-consistent? [labels]
  (and (not (is-single? labels))
       (not (is-resolved? labels))
       (not (is-conflict? labels))))

(defn- search-text-filter [input]
  (if (empty? input)
    (constantly true)
    (let [input-toks (->> (str/split input #" ")
                          (map canonical-keyword))]
      (fn [{:keys [title]}]
        (let [canon-title (canonical-keyword title)]
          (every? #(str/includes? canon-title %)
                  input-toks))))))

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
    (constantly true)))

(defn- inclusion-status-filter [status group-status]
  (if (nil? status)
    (constantly true)
    (fn [entries]
      (let [entries (if (= group-status :resolved)
                      (filter :resolve entries)
                      entries)
            inclusion (->> entries (map :inclusion) distinct)]
        (in? inclusion status)))))

(defn- answer-value-filter [value group-status]
  (if (nil? value)
    (constantly true)
    (fn [entries]
      (let [entries (if (= group-status :resolved)
                      (filter :resolve entries)
                      entries)
            answers (->> entries
                         (map :answer)
                         (map #(if (sequential? %) % [%]))
                         (apply concat)
                         distinct)]
        (in? answers value)))))

(reg-sub
 ::articles
 (fn [[_ panel]]
   [(subscribe (all-articles-sub panel))])
 (fn [[articles]] articles))

;; Gets full list of article entries to display based on selected filters
(reg-sub
 :article-list/filtered
 (fn [[_ panel]]
   [(subscribe [:project/overall-label-id])
    (subscribe [::articles panel])
    (subscribe [:article-list/filter-value :search-text panel])
    (subscribe [:article-list/filter-value :confirm-status panel])
    (subscribe [:article-list/filter-value :label-id panel])
    (subscribe [:article-list/filter-value :group-status panel])
    (subscribe [:article-list/filter-value :answer-value panel])
    (subscribe [:article-list/filter-value :inclusion-status panel])])
 (fn [[overall-id articles search-text confirm-status
       label-id group-status answer-value inclusion-status]]
   (let [not-nil? (complement nil?)

         get-labels #(get-in % [:labels label-id])
         get-overall #(get-in % [:labels overall-id])

         search-text-test
         (when (not-empty search-text)
           (search-text-filter search-text))

         confirm-status-test
         (when (not-nil? confirm-status)
           (confirm-status-filter confirm-status))

         inclusion-status-test
         (when (and overall-id (not-nil? inclusion-status))
           (comp (inclusion-status-filter inclusion-status group-status)
                 get-overall))

         group-status-test
         (when (and overall-id (not-nil? group-status))
           (comp (group-status-filter group-status)
                 get-overall))

         answer-value-test
         (when (and label-id (not-nil? answer-value))
           (comp (answer-value-filter answer-value group-status)
                 get-labels))]
     (cond->> articles
       label-id
       (filterv (comp not-empty get-labels))

       (not-nil? search-text-test)
       (filterv search-text-test)

       (not-nil? inclusion-status-test)
       (filterv inclusion-status-test)

       (not-nil? confirm-status-test)
       (filterv confirm-status-test)

       (not-nil? group-status-test)
       (filterv group-status-test)

       (not-nil? answer-value-test)
       (filterv answer-value-test)))))

;; Gets active value of a filter option from user selection or defaults
(reg-sub
 :article-list/filter-value
 (fn [[_ key panel]]
   [(subscribe [:panel-field [:filters key] panel])
    (subscribe (default-filters-sub panel))])
 (fn [[value defaults] [_ key panel]]
   (if (nil? value)
     (get defaults key)
     value)))

(reg-event-fx
 :article-list/set-filter-value
 [trim-v]
 (fn [_ [key value panel]]
   {:dispatch [:set-panel-field [:filters key] value panel]}))

;; Resets all filter values, except the keys passed in vector `keep`
(reg-event-fx
 ::reset-filters
 [trim-v]
 (fn [{:keys [db]} [keep panel]]
   (let [filter-keys (keys (get-panel-field db [:filters] panel))]
     {:dispatch-n
      (concat
       [[::set-display-offset 0]]
       (->> filter-keys
            (remove #(in? keep %))
            (map (fn [key]
                   [:article-list/set-filter-value key nil panel]))))})))

;; Update state to enable display of `article-id`
(reg-event-fx
 :article-list/show-article
 [trim-v]
 (fn [_ [article-id panel]]
   {:dispatch-n
    (list [:set-panel-field [:article-id] article-id panel]
          [:set-panel-field [:selected-article-id] article-id panel])}))

;; Update state to hide any currently displayed article
;; and render the list interface.
(reg-event-fx
 :article-list/hide-article
 [trim-v]
 (fn [_ [panel]]
   {:dispatch [:set-panel-field [:article-id] nil panel]}))

;; Gets id of article currently being individually displayed
(reg-sub
 :article-list/article-id
 (fn [[_ panel]]
   [(subscribe [:active-panel])
    (subscribe [:panel-field [:article-id] panel])])
 (fn [[active-panel article-id] [_ panel]]
   (when (= active-panel panel)
     article-id)))

;; Gets id of most recent article to have been individually displayed,
;; so it can be indicated visually in list interface.
(reg-sub
 ::selected-article-id
 (fn [[_ panel]]
   [(subscribe [:panel-field [:selected-article-id] panel])])
 (fn [[article-id]] article-id))

;; Get ids of articles immediately before/after active id in filtered list.
;; Will return nil if active id is not currently present in list.
(reg-sub
 ::next-article-id
 (fn [[_ panel]]
   [(subscribe [:article-list/article-id panel])
    (subscribe [:article-list/filtered panel])])
 (fn [[article-id articles]]
   (when (some #(= (:article-id %) article-id) articles)
     (->> articles
          (drop-while #(not= (:article-id %) article-id))
          (drop 1)
          first
          :article-id))))
;;
(reg-sub
 ::prev-article-id
 (fn [[_ panel]]
   [(subscribe [:article-list/article-id panel])
    (subscribe [:article-list/filtered panel])])
 (fn [[article-id articles]]
   (when (some #(= (:article-id %) article-id) articles)
     (->> articles
          (take-while #(not= (:article-id %) article-id))
          last
          :article-id))))

;; Offset index for visible articles within filtered list
(reg-sub
 ::display-offset
 (fn [[_ panel]]
   [(subscribe [:panel-field [:display-offset] panel])])
 (fn [[offset]] (or offset 0)))
;;
(reg-sub
 ::max-display-offset
 (fn [[_ panel]]
   [(subscribe [:article-list/filtered panel])])
 (fn [[articles]]
   (* display-count (quot (dec (count articles)) display-count))))
;;
(reg-event-fx
 ::set-display-offset
 [trim-v]
 (fn [{:keys [db]} [new-offset panel]]
   {:dispatch [:set-panel-field [:display-offset] new-offset panel]}))

(reg-sub-raw
 ::resolving-allowed?
 (fn [_ [_ panel]]
   (reaction
    (boolean
     (when-let [article-id @(subscribe [:article-list/article-id panel])]
       (and (not (private-article-view? panel))
            (= :conflict @(subscribe [:article/review-status article-id]))
            @(subscribe [:member/resolver?])))))))

(reg-sub-raw
 ::editing-allowed?
 (fn [_ [_ panel]]
   (reaction
    (boolean
     (when-let [article-id @(subscribe [:article-list/article-id panel])]
       (or @(subscribe [::resolving-allowed? panel])
           (in? [:confirmed :unconfirmed]
                @(subscribe [:article/user-status article-id]))))))))

(reg-sub-raw
 :article-list/editing?
 (fn [_ [_ panel]]
   (reaction
    (boolean
     (when-let [article-id @(subscribe [:article-list/article-id panel])]
       (and @(subscribe [::editing-allowed? panel])
            (or (and (private-article-view? panel)
                     (= :unconfirmed @(subscribe [:article/user-status article-id])))
                @(subscribe [:review/change-labels? article-id panel]))))))))

(reg-sub
 :article-list/resolving?
 (fn [[_ panel]]
   [(subscribe [:article-list/editing? panel])
    (subscribe [::resolving-allowed? panel])])
 (fn [[editing? resolving-allowed?]]
   (boolean (and editing? resolving-allowed?))))

;;;
;;; Input components for controlling article list filters
;;;

(defn- search-text-synced? [panel]
  (= @(subscribe [:article-list/filter-value :search-text panel])
     @(subscribe [:article-list/filter-value :search-text-input panel])))

(defn- search-text-selector [panel]
  (let [input-sub (subscribe [:article-list/filter-value :search-text-input panel])]
    [:div.ui.fluid.icon.input
     {:class (when-not (search-text-synced? panel) "loading")}
     [:input {:type "text" :id "article-search" :name "article-search"
              :value @input-sub
              :on-change
              (fn [event]
                (let [cur-value (-> event .-target .-value)]
                  (dispatch-sync [:article-list/set-filter-value
                                  :search-text-input cur-value panel])
                  (js/setTimeout
                   #(let [later-value @input-sub]
                      (when (= cur-value later-value)
                        (dispatch [:article-list/set-filter-value
                                   :search-text cur-value panel])))
                   500)))}]
     [:i.search.icon]]))

(defn- confirm-status-selector [panel]
  (let [active-status @(subscribe [:article-list/filter-value :confirm-status panel])
        status-name #(cond (nil? %)   "<Any>"
                           (true? %)  "Yes"
                           (false? %) "No")]
    [:div.confirm-status-selector
     [three-state-selection-icons
      #(dispatch [:article-list/set-filter-value :confirm-status % panel])
      active-status
      :icons {false [:i.remove.circle.outline.icon]
              nil   [:i.help.circle.outline.icon]
              true  [:i.check.circle.outline.icon]}]]))
;;;
(defn- label-selector [panel]
  (let [active-id @(subscribe [:article-list/filter-value :label-id panel])
        label-name #(if (nil? %) "<Any>" @(subscribe [:label/display %]))]
    [selection-dropdown
     [:div.text (label-name active-id)]
     (->> (if (allow-null-label? panel)
            (concat [nil] @(subscribe [:project/label-ids]))
            @(subscribe [:project/label-ids]))
          (mapv
           (fn [label-id]
             [:div.item
              {:key (str label-id)
               :class (when (= label-id active-id)
                        "active selected")
               :on-click #(do (dispatch [:article-list/set-filter-value
                                         :label-id label-id panel])
                              (dispatch [::reset-filters
                                         [:label-id :confirm-status :inclusion-status :group-status]
                                         panel]))}
              (label-name label-id)])))]))
;;;
(defn- group-status-selector [panel]
  (let [active-status @(subscribe [:article-list/filter-value :group-status panel])
        status-name #(if (nil? %) "<Any>" (-> % name str/capitalize))]
    [selection-dropdown
     [:div.text (status-name active-status)]
     (->> (concat [nil] group-statuses)
          (mapv
           (fn [status]
             [:div.item
              {:key status
               :class (when (= status active-status)
                        "active selected")
               :on-click
               #(do (dispatch [:article-list/set-filter-value
                               :group-status status panel])
                    (when (= status :conflict)
                      (dispatch [:article-list/set-filter-value
                                 :inclusion-status nil panel])))}
              (status-name status)])))]))
;;;
(defn- inclusion-status-selector [panel]
  (let [active-status @(subscribe [:article-list/filter-value :inclusion-status panel])
        status-name #(if (nil? %) "<Any>" (str %))]
    [three-state-selection-icons
     #(dispatch [:article-list/set-filter-value :inclusion-status % panel])
     active-status]))
;;;
(defn- answer-value-selector [panel]
  (let [label-id @(subscribe [:article-list/filter-value :label-id panel])]
    (let [all-values @(subscribe [:label/all-values label-id])
          active-value @(subscribe [:article-list/filter-value :answer-value panel])]
      [selection-dropdown
       [:div.text
        (if (nil? active-value) "<Any>" (str active-value))]
       (vec
        (concat
         [[:div.item {:on-click #(dispatch [:article-list/set-filter-value
                                            :answer-value nil panel])}
           "<Any>"]]
         (->> all-values
              (mapv
               (fn [value]
                 [:div.item
                  {:key (str value)
                   :class (when (= value active-value)
                            "active selected")
                   :on-click #(dispatch [:article-list/set-filter-value
                                         :answer-value value panel])}
                  (str value)])))))])))

(defn- article-list-filter-form [panel]
  (let [label-id @(subscribe [:article-list/filter-value :label-id panel])
        overall-id @(subscribe [:project/overall-label-id])
        [overall? boolean? all-values criteria?]
        (when label-id
          [@(subscribe [:label/overall-include? label-id])
           @(subscribe [:label/boolean? label-id])
           @(subscribe [:label/all-values label-id])
           @(subscribe [:label/inclusion-criteria? label-id])])
        group-status @(subscribe [:article-list/filter-value :group-status panel])
        user-id @(subscribe [:self/user-id])
        user-labels-page? (= panel [:project :user :labels])
        select-inclusion? (boolean overall-id)
        select-group-status? (and overall-id (not user-labels-page?))
        select-answer? (and label-id (not-empty all-values))
        select-confirmed? user-labels-page?

        render-field
        (fn [width input-view label-text help-content disable?]
          [:div {:class (if (nil? width)
                          (str (if disable? "disabled" "") " field")
                          (str width " wide "
                               (if disable? "disabled" "") " field"))}
           (doall
            (with-ui-help-tooltip
              [:label label-text " " [ui-help-icon :size ""]]
              :help-content help-content))
           [input-view panel]])

        inclusion-status-field
        (fn [width]
          [render-field width inclusion-status-selector "Inclusion"
           ["Filter by inclusion status for article"]])

        label-field
        (fn [width]
          [render-field width label-selector "Label"
           ["Filter articles by answers for this label"]])

        group-status-field
        (fn [width]
          [render-field width group-status-selector "Group Status"
           ["Filter by group agreement on article inclusion status"]])

        answer-value-field
        (fn [width disable?]
          [render-field width answer-value-selector "Answer Value"
           ["Filter by presence of answer value for selected label"]
           disable?])

        confirmed-field
        (fn [width]
          [render-field width confirm-status-selector "Confirmed"
           ["Filter by whether your answers are confirmed or in-progress"]])

        search-field
        (fn [width]
          [render-field width search-text-selector
           "Text Search"
           ["Filter by matching keywords in article title"]])

        refresh-button
        [:div.ui.right.labeled.icon.button.refresh-button
         {:class (if (loading-articles? panel user-id) "loading" "")
          :style {:width "100%"}
          :on-click #(reload-articles panel user-id)}
         [:i.repeat.icon]
         (if (full-size?) "Refresh Articles" "Refresh")]

        reset-button
        [:div.ui.right.labeled.icon.button.reset-button
         {:style {:width "100%"}
          :on-click
          (if (allow-null-label? panel)
            #(dispatch [::reset-filters [] panel])
            #(dispatch [::reset-filters [] panel]))}
         [:i.remove.icon]
         "Reset Filters"]]
    [:div.ui.secondary.segment.article-filters
     {:style {:padding "10px"}}
     (if (not (mobile?))
       ;; non-mobile view
       [:div.ui.small.form
        [:div.field
         [:div.fields
          (when select-inclusion?    [inclusion-status-field "three"])
          (when select-group-status? [group-status-field "three"])
          (when select-confirmed?    [confirmed-field "three"])
          [search-field "six"]
          [:div.four.wide.field [:label nbsp] reset-button]]]
        [:div.field
         [:div.fields
          [label-field "three"]
          [answer-value-field "three" (not select-answer?)]
          [:div.six.wide.field]
          [:div.four.wide.field [:label nbsp] refresh-button]]]]
       ;; mobile view
       [:div.ui.small.form
        [:div.two.fields.mobile-group
         (when select-inclusion?    [inclusion-status-field nil])
         (when select-group-status? [group-status-field nil])
         (when select-confirmed?    [confirmed-field nil])]
        [:div.two.fields.mobile-group
         [label-field nil]
         [answer-value-field nil (not select-answer?)]]
        [search-field nil]
        [:div.two.fields.mobile-group
         [:div.field refresh-button]
         [:div.field reset-button]]])]))

(reg-sub
 ::article-list-header-text
 (fn [[_ panel]]
   [(subscribe [:article-list/filtered panel])
    (subscribe [::display-offset panel])])
 (fn [[filtered display-offset]]
   (let [total-count (count filtered)]
     (if (zero? total-count)
       "No matching articles found"
       (str "Showing " (+ display-offset 1)
            "-" (min total-count (+ display-offset display-count))
            " of "
            total-count " matching articles ")))))

(defn- article-list-header-message [panel]
  (let [text @(subscribe [::article-list-header-text panel])
        tooltip-content (list-header-tooltip panel)]
    (if (nil? tooltip-content)
      [:h5.no-margin text]
      [:div
       (with-ui-help-tooltip
         [:h5.no-margin text [ui-help-icon]]
         :help-content tooltip-content)])))

;; Render directional navigation buttons for article list interface
(defn- article-list-header-buttons [panel]
  (let [total-count (count @(subscribe [:article-list/filtered panel]))
        max-display-offset @(subscribe [::max-display-offset panel])
        display-offset @(subscribe [::display-offset panel])
        on-first #(dispatch [::set-display-offset 0 panel])
        on-last #(dispatch [::set-display-offset max-display-offset panel])
        on-next
        #(when (< (+ display-offset display-count) total-count)
           (dispatch [::set-display-offset
                      (+ display-offset display-count) panel]))
        on-previous
        #(when (>= display-offset display-count)
           (dispatch [::set-display-offset
                      (max 0 (- display-offset display-count)) panel]))]
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

(reg-sub
 ::visible-entries
 (fn [[_ panel]]
   [(subscribe [:article-list/filtered panel])
    (subscribe [::display-offset panel])])
 (fn [[articles display-offset]]
   (->> articles
        (drop display-offset)
        (take display-count))))

(defn- article-list-view-articles [panel]
  (let [active-aid @(subscribe [::selected-article-id panel])
        show-article #(nav (article-uri panel %))
        full-size? (full-size?)]
    [:div
     (doall
      (->>
       @(subscribe [::visible-entries panel])
       (map
        (fn [{:keys [article-id] :as article}]
          (let [active? (= article-id active-aid)
                classes (if active? "active" "")
                loading? @(subscribe [:loading? [:article article-id]])]
            [:div.article-list-segments
             {:key article-id}
             [:div.ui.middle.aligned.grid.segment.article-list-article
              {:class (str (if active? "active" "")
                           " "
                           (if loading? "article-loading" ""))
               :on-click #(show-article article-id)}
              (when loading?
                [:div.ui.active.inverted.dimmer
                 [:div.ui.loader]])
              [render-article-entry panel article full-size?]]])))))]))

(defn- article-list-list-view [panel]
  (let [user-id @(subscribe [:self/user-id])]
    (if (full-size?)
      [:div.article-list-view
       [:div.ui.top.attached.segment.article-nav
        [:div.ui.two.column.middle.aligned.grid
         [:div.ui.left.aligned.column
          [article-list-header-message panel]]
         [article-list-header-buttons panel]]]
       [:div.ui.bottom.attached.segment.article-list-segment
        [article-list-view-articles panel]]]
      [:div.article-list-view
       [:div.ui.segment.article-nav
        [:div.ui.middle.aligned.grid
         [:div.ui.left.aligned.seven.wide.column
          [article-list-header-message panel]]
         [article-list-header-buttons panel]]]
       [:div.article-list-segment
        [article-list-view-articles panel]]])))

(defn- article-list-article-view [article-id panel]
  (let [label-values @(subscribe [:review/active-labels article-id])
        overall-label-id @(subscribe [:project/overall-label-id])
        user-id @(subscribe [:self/user-id])
        user-status @(subscribe [:article/user-status article-id user-id])
        editing-allowed? @(subscribe [::editing-allowed? panel])
        resolving-allowed? @(subscribe [::resolving-allowed? panel])
        editing? @(subscribe [:article-list/editing? panel])
        resolving? @(subscribe [:article-list/resolving? panel])
        close-article #(nav (panel-base-uri panel))
        next-id @(subscribe [::next-article-id panel])
        prev-id @(subscribe [::prev-article-id panel])
        next-loading? (when next-id @(subscribe [:loading? [:article next-id]]))
        prev-loading? (when prev-id @(subscribe [:loading? [:article prev-id]]))
        back-loading? (loading-articles? panel user-id)
        prev-class (str (if (nil? prev-id) "disabled" "")
                        " " (if prev-loading? "loading" ""))
        next-class (str (if (nil? next-id) "disabled" "")
                        " " (if next-loading? "loading" ""))
        back-class (str (if back-loading? "loading" ""))
        on-next #(when next-id (nav (article-uri panel next-id)))
        on-prev #(when prev-id (nav (article-uri panel prev-id)))]
    [:div.article-view
     (if (full-size?)
       ;; non-mobile view
       [:div.ui.top.attached.segment.article-nav
        {:style {:padding "10px"}}
        [:div.ui.three.column.middle.aligned.grid
         [:div.ui.left.aligned.column
          [:div.ui.tiny.fluid.button {:class back-class :on-click close-article}
           [:span {:style {:float "left"}}
            [:i.list.icon]]
           "Back to list"]]
         [:div.ui.center.aligned.column]
         [:div.ui.right.aligned.column
          [:div.ui.tiny.buttons
           [:div.ui.tiny.button {:class prev-class :on-click on-prev}
            [:i.chevron.left.icon] "Previous"]
           [:div.ui.tiny.button {:class next-class :on-click on-next}
            "Next" [:i.chevron.right.icon]]]]]]
       ;; mobile view
       [:div.ui.segment.article-nav
        [:div.ui.middle.aligned.grid
         [:div.ui.six.wide.left.aligned.column
          [:div.ui.tiny.fluid.button {:class back-class :on-click close-article}
           [:span {:style {:float "left"}}
            [:i.list.icon]]
           "Back to list"]]
         [:div.ui.ten.wide.right.aligned.column
          [:div.ui.tiny.buttons
           [:div.ui.tiny.button {:class prev-class :on-click on-prev}
            [:i.chevron.left.icon] "Previous"]
           [:div.ui.tiny.button {:class next-class :on-click on-next}
            "Next" [:i.chevron.right.icon]]]]]])
     [:div
      {:class (if (full-size?)
                "ui bottom attached middle aligned segment"
                "")}
      [:div
       [article-info-view article-id
        :show-labels? true
        :private-view? (private-article-view? panel)]
       (cond editing?
             [label-editor-view article-id]

             editing-allowed?
             [:div.ui.segment
              [:div.ui.fluid.button
               {:on-click #(dispatch [:review/enable-change-labels article-id panel])}
               (if resolving-allowed? "Resolve Labels" "Change Labels")]])]]]))

;; Top-level component for article list interface
(defn article-list-view [panel & [loader-items]]
  (let [article-id @(subscribe [:article-list/article-id panel])]
    [:div
     [article-list-filter-form panel]
     (with-loader (concat [[:project]] loader-items) {}
       (if article-id
         [article-list-article-view article-id panel]
         [article-list-list-view panel]))]))
