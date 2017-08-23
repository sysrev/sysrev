(ns sysrev.views.article-list
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-sub-raw
     reg-event-db reg-event-fx reg-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.views.article :refer [article-info-view]]
   [sysrev.views.review :refer [label-editor-view]]
   [sysrev.views.components :refer
    [with-ui-help-tooltip ui-help-icon selection-dropdown three-state-selection-icons]]
   [sysrev.subs.ui :refer [get-panel-field]]
   [sysrev.routes :refer [nav]]
   [sysrev.util :refer [full-size? number-to-word nbsp]]
   [sysrev.shared.util :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defmulti default-filters-sub (fn [panel] panel))
(defmulti panel-base-uri (fn [panel] panel))
(defmulti article-uri (fn [panel _] panel))
(defmulti all-articles-sub (fn [panel] panel))
(defmulti allow-null-label? (fn [panel] panel))
(defmulti list-header-tooltip (fn [panel] panel))
(defmulti render-article-entry (fn [panel article full-size?] panel))

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
  (and (not (is-resolved? labels))
       (not (is-conflict? labels))
       (not (is-single? labels))))

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
   [(subscribe [::articles panel])
    (subscribe [:article-list/filter-value :confirm-status panel])
    (subscribe [:article-list/filter-value :label-id panel])
    (subscribe [:article-list/filter-value :group-status panel])
    (subscribe [:article-list/filter-value :answer-value panel])
    (subscribe [:article-list/filter-value :inclusion-status panel])])
 (fn [[articles confirm-status label-id group-status answer-value inclusion-status]]
   (let [get-labels #(get-in % [:labels label-id])
         filter-labels
         (fn [articles]
           (if (nil? label-id)
             articles
             (->> articles
                  (filter (comp not-empty get-labels))
                  (filter (comp (group-status-filter group-status) get-labels))
                  (filter (comp (answer-value-filter
                                 answer-value group-status) get-labels))
                  (filter (comp (inclusion-status-filter
                                 inclusion-status group-status) get-labels)))))]
     (->> articles
          (filter (confirm-status-filter confirm-status))
          (filter-labels)
          ;; Would be sorted here, but sorted by :updated-time on server
          ))))

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
 :article-list/editing?
 (fn [_ [_ panel]]
   (reaction
    (boolean
     (let [article-id @(subscribe [:article-list/article-id panel])
           user-id @(subscribe [:self/user-id])
           change-labels? @(subscribe [:review/change-labels? article-id panel])]
       (when (and article-id user-id)
         (let [user-status @(subscribe [:article/user-status article-id user-id])
               review-status @(subscribe [:article/review-status article-id])
               resolving? (and (= review-status :conflict)
                               @(subscribe [:member/resolver? user-id]))]
           (or (= user-status :unconfirmed)
               change-labels?
               resolving?))))))))

(reg-sub-raw
 :article-list/resolving?
 (fn [_ [_ panel]]
   (reaction
    (boolean
     (when @(subscribe [:article-list/editing? panel])
       (let [article-id @(subscribe [:article-list/article-id panel])
             user-id @(subscribe [:self/user-id])]
         (when (and article-id user-id)
           (let [review-status @(subscribe [:article/review-status article-id])]
             (and (= review-status :conflict)
                  @(subscribe [:member/resolver? user-id]))))))))))

;;;
;;; Input components for controlling article list filters
;;;
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
                              (dispatch [::reset-filters [:label-id :confirm-status] panel]))}
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
        [overall? boolean? all-values criteria?]
        (when label-id
          [@(subscribe [:label/overall-include? label-id])
           @(subscribe [:label/boolean? label-id])
           @(subscribe [:label/all-values label-id])
           @(subscribe [:label/inclusion-criteria? label-id])])
        group-status @(subscribe [:article-list/filter-value :group-status panel])
        user-labels-page? (= panel [:project :user :labels])
        select-group-status? (and label-id criteria? (not user-labels-page?))
        select-inclusion? (and label-id criteria?
                               (not= group-status :conflict))
        select-answer? (and label-id
                            (not (and boolean? criteria?))
                            (not-empty all-values))
        select-confirmed? user-labels-page?
        n-columns (+ 3
                     (if select-group-status? 3 0)
                     (if select-answer? 3 0)
                     (if select-inclusion? 2 0)
                     (if select-confirmed? 2 0)
                     2)
        whitespace-columns (- 16 n-columns)]
    [:div.ui.secondary.segment.article-filters
     {:style {:padding "10px"}}
     [:form.ui.form
      [:div.field
       [:div.fields
        [:div.ui.small.three.wide.field
         (doall
          (with-ui-help-tooltip
            [:label "Label " [ui-help-icon :size ""]]
            :help-content
            ["Filter articles by answers for this label"]))
         [label-selector panel]]
        (when select-group-status?
          [:div.ui.small.three.wide.field
           (doall
            (with-ui-help-tooltip
              [:label "Group Status " [ui-help-icon :size ""]]
              :help-content
              ["Filter by group agreement on inclusion status for selected label"]))
           [group-status-selector panel]])
        (when select-answer?
          [:div.ui.small.three.wide.field
           (doall
            (with-ui-help-tooltip
              [:label "Answer Value " [ui-help-icon :size ""]]
              :help-content
              ["Filter by presence of answer value for selected label"]))
           [answer-value-selector panel]])
        (when select-inclusion?
          [:div.ui.small.two.wide.field
           (doall
            (with-ui-help-tooltip
              [:label "Inclusion Status " [ui-help-icon :size ""]]
              :help-content
              ["Filter by answer inclusion status for selected label"]))
           [inclusion-status-selector panel]])
        (when select-confirmed?
          [:div.ui.small.two.wide.field
           (doall
            (with-ui-help-tooltip
              [:label "Confirmed " [ui-help-icon :size ""]]
              :help-content
              ["Filter by whether your answers are confirmed or in-progress"]))
           [confirm-status-selector panel]])
        (when (full-size?)
          [:div {:class (str (number-to-word whitespace-columns)
                             " wide field")}])
        [:div.ui.small.two.wide.field
         [:label nbsp]
         [:div.ui.button
          {:on-click
           (if (allow-null-label? panel)
             #(dispatch [::reset-filters [] panel])
             #(dispatch [::reset-filters [] panel]))}
          "Reset filters"]]]]]]))

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
      [:i.angle.double.right.icon]]]))

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
        show-article #(nav (article-uri panel %))]
    [:div
     (doall
      (->>
       @(subscribe [::visible-entries panel])
       (map
        (fn [{:keys [article-id] :as article}]
          (let [active? (= article-id active-aid)
                classes (if active? "active" "")]
            [:div.article-list-segments
             {:key article-id}
             [:div.ui.middle.aligned.grid.segment.article-list-article
              {:class (if active? "active" "")
               :on-click #(show-article article-id)}
              [render-article-entry panel article full-size?]]])))))]))

(defn- article-list-list-view [panel]
  [:div.article-list-view
   [:div.ui.top.attached.segment
    [:div.ui.two.column.middle.aligned.grid
     [:div.ui.left.aligned.column
      [article-list-header-message panel]]
     [article-list-header-buttons panel]]]
   [:div.ui.bottom.attached.segment.article-list-segment
    [article-list-view-articles panel]]])

(defn- article-list-article-view [article-id panel]
  (let [label-values @(subscribe [:review/active-labels article-id])
        overall-label-id @(subscribe [:project/overall-label-id])
        user-id @(subscribe [:self/user-id])
        user-status @(subscribe [:article/user-status article-id user-id])
        editing? @(subscribe [:article-list/editing? panel])
        resolving? @(subscribe [:article-list/resolving? panel])
        close-article #(nav (panel-base-uri panel))
        next-id @(subscribe [::next-article-id panel])
        prev-id @(subscribe [::prev-article-id panel])
        on-next #(when next-id (nav (article-uri panel next-id)))
        on-prev #(when prev-id (nav (article-uri panel prev-id)))]
    [:div
     [:div.ui.top.attached.segment
      {:style {:padding "10px"}}
      [:div.ui.three.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        [:div.ui.tiny.fluid.button {:on-click close-article}
         [:span {:style {:float "left"}}
          [:i.list.icon]]
         "Back to list"]]
       [:div.ui.center.aligned.column]
       [:div.ui.right.aligned.column
        [:div.ui.tiny.buttons
         [:div.ui.tiny.button
          {:class (if (nil? prev-id) "disabled" "")
           :on-click on-prev}
          [:i.chevron.left.icon] "Previous"]
         [:div.ui.tiny.button
          {:class (if (nil? next-id) "disabled" "")
           :on-click on-next}
          "Next" [:i.chevron.right.icon]]]]]]
     [:div.ui.bottom.attached.middle.aligned.segment
      [:div
       (let [show-labels?
             (case panel
               [:project :user :labels] false
               :all)]
         [article-info-view article-id :show-labels? show-labels?])
       (cond editing?
             [label-editor-view article-id]

             (= user-status :confirmed)
             [:div.ui.segment
              [:div.ui.fluid.button
               {:on-click #(dispatch [:review/enable-change-labels article-id panel])}
               "Change Answers"]])]]]))

;; Top-level component for article list interface
(defn article-list-view [panel & [loader-items]]
  [:div
   [article-list-filter-form panel]
   (with-loader (concat [[:project]] loader-items) {}
     (if-let [article-id @(subscribe [:article-list/article-id panel])]
       [article-list-article-view article-id panel]
       [article-list-list-view panel]))])
