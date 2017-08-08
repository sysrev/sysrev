(ns sysrev.views.panels.project.article-list
  (:require
   [clojure.string :as str]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer
    [selection-dropdown with-ui-help-tooltip ui-help-icon]]
   [sysrev.views.article :refer [article-info-view]]
   [sysrev.views.review :refer [label-editor-view]]
   [sysrev.subs.ui :refer [get-panel-field]]
   [sysrev.subs.public-labels :as pl]
   [sysrev.routes :refer [nav nav-scroll-top]]
   [sysrev.util :refer [nbsp full-size? number-to-word]]
   [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel-name [:project :project :articles])

(reg-sub
 :article-list/label-id
 (fn []
   [(subscribe [:panel-field [:label-id] panel-name])
    (subscribe [:project/overall-label-id])])
 (fn [[label-id default]]
   (or label-id default)))
(reg-event-fx
 ::set-label-id
 [trim-v]
 (fn [_ [label-id]]
   {:dispatch [:set-panel-field [:label-id] label-id panel-name]}))

(reg-sub
 ::group-status
 :<- [:panel-field [:group-status] panel-name]
 (fn [status] status))
(reg-event-fx
 ::set-group-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:set-panel-field [:group-status] status panel-name]}))

(reg-sub
 ::inclusion-status
 :<- [:panel-field [:inclusion-status] panel-name]
 (fn [status] status))
(reg-event-fx
 ::set-inclusion-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:set-panel-field [:inclusion-status] status panel-name]}))

(reg-sub
 ::answer-value
 :<- [:panel-field [:answer-value] panel-name]
 (fn [value] value))
(reg-event-fx
 ::set-answer-value
 [trim-v]
 (fn [_ [value]]
   {:dispatch [:set-panel-field [:answer-value] value panel-name]}))

(reg-event-fx
 ::reset-filters
 [trim-v]
 (fn [_ [keep]]
   {:dispatch-n
    (->> (list (when-not (in? keep :label-id)
                 [::set-label-id nil])
               (when-not (in? keep :group-status)
                 [::set-group-status nil])
               (when-not (in? keep :answer-value)
                 [::set-answer-value nil])
               (when-not (in? keep :inclusion-status)
                 [::set-inclusion-status nil]))
         (remove nil?))}))

(reg-sub-raw
 ::articles
 (fn [_ _]
   (reaction
    (when-let [label-id @(subscribe [:article-list/label-id])]
      @(subscribe
        [:public-labels/query-articles
         label-id {:group-status @(subscribe [::group-status])
                   :answer-value @(subscribe [::answer-value])
                   :inclusion-status @(subscribe [::inclusion-status])}])))))

(reg-event-fx
 :article-list/show-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch-n
    (list [:set-panel-field [:article-id] article-id panel-name]
          [:set-panel-field [:selected-article-id] article-id panel-name])}))
(reg-event-fx
 :article-list/hide-article
 [trim-v]
 (fn []
   {:dispatch [:set-panel-field [:article-id] nil panel-name]}))
(reg-sub
 :article-list/article-id
 (fn []
   [(subscribe [:active-panel])
    (subscribe [:panel-field [:article-id] panel-name])])
 (fn [[active-panel article-id]]
   (when (= active-panel panel-name)
     article-id)))
(reg-sub
 ::selected-article-id
 :<- [:panel-field [:selected-article-id] panel-name]
 identity)

(reg-sub
 ::next-article-id
 :<- [:article-list/article-id]
 :<- [::articles]
 (fn [[article-id articles]]
   (when (some #(= (:article-id %) article-id) articles)
     (->> articles
          (drop-while #(not= (:article-id %) article-id))
          (drop 1)
          first
          :article-id))))

(reg-sub
 ::prev-article-id
 :<- [:article-list/article-id]
 :<- [::articles]
 (fn [[article-id articles]]
   (when (some #(= (:article-id %) article-id) articles)
     (->> articles
          (take-while #(not= (:article-id %) article-id))
          last
          :article-id))))

(reg-sub-raw
 :article-list/editing?
 (fn []
   (reaction
    (boolean
     (let [article-id @(subscribe [:article-list/article-id])
           user-id @(subscribe [:self/user-id])]
       (when (and article-id user-id)
         (let [user-status @(subscribe [:article/user-status article-id user-id])
               review-status @(subscribe [:article/review-status article-id])
               resolving? (and (= review-status "conflict")
                               @(subscribe [:member/resolver? user-id]))]
           (or (= user-status :unconfirmed) resolving?))))))))

(reg-sub-raw
 :article-list/resolving?
 (fn []
   (reaction
    (boolean
     (when @(subscribe [:article-list/editing?])
       (let [article-id @(subscribe [:article-list/article-id])
             user-id @(subscribe [:self/user-id])]
         (when (and article-id user-id)
           (let [review-status @(subscribe [:article/review-status article-id])]
             (and (= review-status "conflict")
                  @(subscribe [:member/resolver? user-id]))))))))))

(reg-sub
 ::display-offset
 (fn []
   [(subscribe [:panel-field [:display-offset] panel-name])])
 (fn [[offset]] (or offset 0)))

(reg-event-fx
 ::change-display-offset
 [trim-v]
 (fn [{:keys [db]} [delta]]
   (let [offset (or (get-panel-field db [:display-offset] panel-name) 0)
         new-offset (+ offset delta)]
     {:dispatch [:set-panel-field [:display-offset] new-offset panel-name]})))

(defn- label-selector []
  (let [active-id @(subscribe [:article-list/label-id])]
    [selection-dropdown
     [:div.text @(subscribe [:label/display active-id])]
     (->> @(subscribe [:project/label-ids])
          (mapv
           (fn [label-id]
             [:div.item
              (into {:key label-id
                     :on-click #(do (dispatch [::set-label-id label-id])
                                    (dispatch [::reset-filters [:label-id]]))}
                    (when (= label-id active-id)
                      {:class "active selected"}))
              @(subscribe [:label/display label-id])])))]))

(defn- group-status-selector []
  (let [active-status @(subscribe [::group-status])
        status-name #(if (nil? %) "<Any>" (-> % name str/capitalize))]
    [selection-dropdown
     [:div.text (status-name active-status)]
     (->> (concat [nil] pl/group-statuses)
          (mapv
           (fn [status]
             [:div.item
              (into {:key status
                     :on-click #(do (dispatch [::set-group-status status])
                                    (when (= status :conflict)
                                      (dispatch [::set-inclusion-status nil])))}
                    (when (= status active-status)
                      {:class "active selected"}))
              (status-name status)])))]))

(defn- inclusion-status-selector []
  (let [active-status @(subscribe [::inclusion-status])
        status-name #(if (nil? %) "<Any>" (str %))]
    [selection-dropdown
     [:div.text (status-name active-status)]
     (->> [nil true false]
          (mapv
           (fn [status]
             [:div.item
              (into {:key status
                     :on-click #(dispatch [::set-inclusion-status status])}
                    (when (= status active-status)
                      {:class "active selected"}))
              (status-name status)])))]))

(defn- answer-value-selector []
  (let [label-id @(subscribe [:article-list/label-id])]
    (let [all-values @(subscribe [:label/all-values label-id])
          active-value @(subscribe [::answer-value])]
      [selection-dropdown
       [:div.text
        (if (nil? active-value) "<Any>" (str active-value))]
       (vec
        (concat
         [[:div.item {:on-click #(dispatch [::set-answer-value nil])}
           "<Any>"]]
         (->> all-values
              (mapv
               (fn [value]
                 [:div.item
                  (into {:key (str value)
                         :on-click #(dispatch [::set-answer-value value])}
                        (when (= value active-value)
                          {:class "active selected"}))
                  (str value)])))))])))

(defn- article-filter-form []
  (when-let [label-id @(subscribe [:article-list/label-id])]
    (let [overall? @(subscribe [:label/overall-include? label-id])
          boolean? @(subscribe [:label/boolean? label-id])
          all-values @(subscribe [:label/all-values label-id])
          criteria? @(subscribe [:label/inclusion-criteria? label-id])
          group-status @(subscribe [::group-status])
          select-answer? (and (not (and boolean? criteria?))
                              (not-empty all-values))
          select-group-status? (and criteria? (not= group-status :conflict))
          n-columns (+ 3
                       (if criteria? 3 0)
                       (if select-group-status? 3 0)
                       (if select-answer? 3 0)
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
           [label-selector]]
          (when criteria?
            [:div.ui.small.three.wide.field
             (doall
              (with-ui-help-tooltip
                [:label "Group Status " [ui-help-icon :size ""]]
                :help-content
                ["Filter by group agreement on inclusion status for selected label"]))
             [group-status-selector]])
          (when select-group-status?
            [:div.ui.small.three.wide.field
             (doall
              (with-ui-help-tooltip
                [:label "Inclusion Status " [ui-help-icon :size ""]]
                :help-content
                ["Filter by answer inclusion status for selected label"]))
             [inclusion-status-selector]])
          (when select-answer?
            [:div.ui.small.three.wide.field
             (doall
              (with-ui-help-tooltip
                [:label "Answer Value " [ui-help-icon :size ""]]
                :help-content
                ["Filter by presence of answer value for selected label"]))
             [answer-value-selector]])
          (when (full-size?)
            [:div {:class (str (number-to-word whitespace-columns)
                               " wide field")}])
          [:div.ui.small.two.wide.field
           [:label nbsp]
           [:div.ui.button
            {:on-click #(dispatch [::reset-filters [:label-id]])}
            "Reset filters"]]]]]])))

(defmulti answer-cell-icon identity)
(defmethod answer-cell-icon true [] [:i.ui.green.circle.plus.icon])
(defmethod answer-cell-icon false [] [:i.ui.orange.circle.minus.icon])
(defmethod answer-cell-icon :default [] [:i.ui.grey.question.mark.icon])

(defn- answer-cell [article-id labels answer-class]
  [:div.ui.divided.list
   (->> labels
        (map (fn [entry]
               (let [user-id (:user-id entry)
                     inclusion (:inclusion entry)]
                 (when (or (not= answer-class "resolved")
                           (:resolve entry))
                   [:div.item {:key [:answer article-id user-id]}
                    (answer-cell-icon inclusion)
                    [:div.content>div.header
                     @(subscribe [:user/display user-id])]]))))
        (doall))])

(defn- article-list-view []
  (let [full-size? (full-size?)
        label-id @(subscribe [:article-list/label-id])
        articles @(subscribe [::articles])
        display-count 10
        total-count (count articles)
        display-offset @(subscribe [::display-offset])
        visible-count (min display-count total-count)
        active-aid @(subscribe [::selected-article-id])
        on-next
        #(when (< (+ display-offset display-count) total-count)
           (dispatch [::change-display-offset display-count]))
        on-previous
        #(when (>= display-offset display-count)
           (dispatch [::change-display-offset (- display-count)]))
        on-show-article
        (fn [article-id]
          #(nav (str "/project/articles/" article-id)))]
    [:div
     [:div.ui.top.attached.segment
      {:style {:padding "10px"}}
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        (doall
         (with-ui-help-tooltip
           [:h5.no-margin
            (str "Showing " (+ display-offset 1)
                 "-" (min total-count (+ display-offset display-count))
                 " of "
                 total-count " matching articles ")
            [ui-help-icon]]
           :help-content
           ["Public listing of articles reviewed by multiple users"
            [:div.ui.divider]
            "Articles are hidden for 12 hours after any edit to labels"]))]
       [:div.ui.right.aligned.column
        [:div.ui.tiny.button
         {:class (if (= display-offset 0) "disabled" "")
          :on-click on-previous}
         "Previous"]
        [:div.ui.tiny.button
         {:class (if (>= (+ display-offset display-count) total-count)
                   "disabled" "")
          :on-click on-next}
         "Next"]]]]
     [:div.ui.bottom.attached.segment.article-list-segment
      (->>
       (->> articles
            (drop display-offset)
            (take display-count))
       (map
        (fn [{:keys [article-id title updated-time labels]}]
          (let [labels (get labels label-id)
                active? (= article-id active-aid)
                answer-class
                (cond
                  (pl/is-resolved? labels) "resolved"
                  (pl/is-consistent? labels) "consistent"
                  (pl/is-single? labels) "single"
                  :else "conflict")
                classes
                (cond-> []
                  active? (conj "active"))]
            [:div.article-list-segments
             {:key article-id}
             [:div.ui.middle.aligned.grid.segment.article-list-article
              {:class (if active? "active" "")
               :style {:cursor "pointer"}
               :on-click (on-show-article article-id)}
              (if full-size?
                [:div.ui.row
                 [:div.ui.one.wide.center.aligned.column
                  [:div.ui.fluid.labeled.center.aligned.button
                   [:i.ui.right.chevron.center.aligned.icon
                    {:style {:width "100%"}}]]]
                 [:div.ui.twelve.wide.column.article-title
                  [:span.article-title title]]
                 [:div.ui.three.wide.center.aligned.middle.aligned.column.article-answers
                  {:class answer-class}
                  [:div.ui.middle.aligned.grid>div.row>div.column
                   [answer-cell article-id labels answer-class]]]]
                [:div.ui.row
                 [:div.ui.ten.wide.column.article-title
                  [:span.article-title title]]
                 [:div.ui.six.wide.center.aligned.middle.aligned.column.article-answers
                  {:class answer-class}
                  [:div.ui.middle.aligned.grid>div.row>div.column
                   [answer-cell article-id labels answer-class]]]])]])))
       (doall))]]))

(defn- article-list-article-view []
  (when-let [article-id @(subscribe [:article-list/article-id])]
    (let [articles @(subscribe [::articles])
          label-values @(subscribe [:review/active-labels article-id])
          overall-label-id @(subscribe [:project/overall-label-id])
          user-id @(subscribe [:self/user-id])
          user-status @(subscribe [:article/user-status article-id user-id])
          editing? @(subscribe [:article-list/editing?])
          resolving? @(subscribe [:article-list/resolving?])
          close-article #(nav "/project/articles")
          next-id @(subscribe [::next-article-id])
          prev-id @(subscribe [::prev-article-id])
          on-next #(when next-id
                     (nav (str "/project/articles/" next-id)))
          on-prev #(when prev-id
                     (nav (str "/project/articles/" prev-id)))
          ;; on-confirm #(dispatch [:article-list/hide-article])
          on-confirm nil]
      [:div
       [:div.ui.top.attached.segment
        {:style {:padding "10px"}}
        [:div.ui.two.column.middle.aligned.grid
         [:div.ui.left.aligned.column
          [:div.ui.tiny.button {:on-click close-article}
           "Back to list"]]
         [:div.ui.right.aligned.column
          [:div.ui.tiny.button
           {:class (if (nil? prev-id) "disabled" "")
            :on-click on-prev}
           "Previous"]
          [:div.ui.tiny.button
           {:class (if (nil? next-id) "disabled" "")
            :on-click on-next}
           "Next"]]]]
       [:div.ui.bottom.attached.middle.aligned.segment
        (with-loader [[:article article-id]] {:dimmer true :min-height "300px"}
          [:div
           (let [show-labels (if (= user-status :unconfirmed) false :all)]
             [article-info-view article-id
              :show-labels show-labels])
           (when editing?
             [:div {:style {:margin-top "1em"}}
              [label-editor-view]
              #_
              (let [missing (labels/required-answers-missing label-values)
                    disabled? ((comp not empty?) missing)
                    confirm-button
                    [:div.ui.right.labeled.icon
                     {:class (str (if disabled? "disabled" "")
                                  " "
                                  (if (get-loading-state :confirm) "loading" "")
                                  " "
                                  (if resolving? "purple button" "primary button"))
                      :on-click
                      (fn []
                        (set-loading-state :confirm true)
                        (ajax/confirm-active-labels on-confirm))}
                     (if resolving? "Resolve conflict" "Confirm labels")
                     [:i.check.circle.outline.icon]]]
                [:div.ui.grid.centered
                 [:div.row
                  (if disabled?
                    [with-tooltip [:div confirm-button]]
                    confirm-button)
                  [:div.ui.inverted.popup.top.left.transition.hidden
                   "Answer missing for a required label"]]])])])]])))

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    (when-let [label-id @(subscribe [:article-list/label-id])]
      [:div
       [article-filter-form]
       (with-loader [[:project/public-labels]] {}
         (if @(subscribe [:article-list/article-id])
           [article-list-article-view]
           [article-list-view]))])))
