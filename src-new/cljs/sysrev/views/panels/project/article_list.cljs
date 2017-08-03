(ns sysrev.views.panels.project.article-list
  (:require
   [clojure.string :as str]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [selection-dropdown]]
   [sysrev.views.article :refer [article-info-view]]
   [sysrev.views.review :refer [label-editor-view]]
   [sysrev.subs.ui :refer [get-panel-field]]
   [sysrev.subs.label-activity :as la]
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
   {:dispatch-n
    (list [:set-panel-field [:label-id] label-id panel-name]
          [::set-answer-value nil])}))

(reg-sub
 ::answer-status
 :<- [:panel-field [:answer-status] panel-name]
 (fn [status] status))
(reg-event-fx
 ::set-answer-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:set-panel-field [:answer-status] status panel-name]}))

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
               (when-not (in? keep :answer-status)
                 [::set-answer-status nil])
               (when-not (in? keep :answer-value)
                 [::set-answer-value nil]))
         (remove nil?))}))

(reg-sub-raw
 ::articles
 (fn [_ _]
   (reaction
    (when-let [label-id @(subscribe [:article-list/label-id])]
      @(subscribe
        [:label-activity/articles
         label-id {:answer-status @(subscribe [::answer-status])
                   :answer-value @(subscribe [::answer-value])}])))))

(reg-event-fx
 ::select-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch [:set-panel-field [:selected-article-id] article-id panel-name]}))
(reg-sub
 ::selected-article-id
 (fn []
   [(subscribe [:panel-field [:selected-article-id] panel-name])])
 (fn [[article-id]] article-id))

(reg-event-fx
 :article-list/show-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch [:set-panel-field [:article-id] article-id panel-name]}))
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
                     :on-click #(dispatch [::set-label-id label-id])}
                    (when (= label-id active-id)
                      {:class "active selected"}))
              @(subscribe [:label/display label-id])])))]))

(defn- answer-status-selector []
  (let [active-status @(subscribe [::answer-status])
        status-name #(if (nil? %) "<Any>" (-> % name str/capitalize))]
    [selection-dropdown
     [:div.text (status-name active-status)]
     (->> (concat [nil] la/answer-statuses)
          (mapv
           (fn [status]
             [:div.item
              (into {:key status
                     :on-click #(dispatch [::set-answer-status status])}
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
    (let [all-values @(subscribe [:label/all-values label-id])
          n-columns (+ 3 3 (if (empty? all-values) 0 3) 2)
          whitespace-columns (- 16 n-columns)]
      [:div.ui.secondary.segment
       {:style {:padding "10px"}}
       [:div.ui.small.dividing.header "Article filters"]
       [:form.ui.form
        [:div.field
         [:div.fields
          [:div.ui.small.three.wide.field
           [:label "Label"]
           [label-selector]]
          [:div.ui.small.three.wide.field
           [:label "Answer status"]
           [answer-status-selector]]
          (when-not (empty? all-values)
            [:div.ui.small.three.wide.field
             [:label "Answer value"]
             [answer-value-selector]])
          (when (full-size?)
            [:div {:class (str (number-to-word whitespace-columns)
                               " wide field")}])
          [:div.ui.small.two.wide.field
           [:label nbsp]
           [:div.ui.button
            {:on-click #(dispatch [::reset-filters])}
            "Reset filters"]]]]]])))

(defmulti answer-cell-icon identity)
(defmethod answer-cell-icon true [] [:i.ui.green.circle.plus.icon])
(defmethod answer-cell-icon false [] [:i.ui.orange.circle.minus.icon])
(defmethod answer-cell-icon :default [] [:i.ui.grey.question.mark.icon])

(defn- answer-cell [label-groups answer-class]
  [:div.ui.divided.list
   (->> (la/user-grouped label-groups)
        (map (fn [u]
               (let [user-id (:user-id u)
                     article-label (:article-label u)
                     article-id (:article-id article-label)
                     answer (:answer article-label)]
                 (when (or (not= answer-class "resolved")
                           (:resolve article-label))
                   [:div.item {:key [:answer article-id user-id]}
                    (answer-cell-icon answer)
                    [:div.content>div.header
                     @(subscribe [:user/display user-id])]]))))
        (doall))])

(defn- article-list-view []
  (when-let [label-id @(subscribe [:article-list/label-id])]
    (with-loader [[:project/label-activity label-id]] {}
      (let [full-size? (full-size?)
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
               (dispatch [::change-display-offset (- display-count)]))]
        [:div
         [:div.ui.top.attached.segment
          {:style {:padding "10px"}}
          [:div.ui.two.column.middle.aligned.grid
           [:div.ui.left.aligned.column
            [:h5 (str "Showing " (+ display-offset 1)
                      "-" (min total-count (+ display-offset display-count))
                      " of "
                      total-count " matching articles")]]
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
            (fn [las]
              (let [article-id (:article-id las)
                    fla (first (:article-labels las))
                    title (:primary-title fla)
                    active? (= article-id active-aid)
                    answer-class
                    (cond
                      (la/is-resolved? las) "resolved"
                      (la/is-concordance? las) "consistent"
                      (la/is-single? las) "single"
                      :else "conflict")
                    classes
                    (cond-> []
                      active? (conj "active"))]
                [:div.article-list-segments
                 {:key article-id}
                 [:div.ui.middle.aligned.grid.segment.article-list-article
                  {:class (if active? "active" "")
                   :style {:cursor "pointer"}
                   :on-click #(do (dispatch [::select-article article-id])
                                  (nav-scroll-top (str "/project/articles/" article-id)))}
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
                       [answer-cell las answer-class]]]]
                    [:div.ui.row
                     [:div.ui.ten.wide.column.article-title
                      [:span.article-title title]]
                     [:div.ui.six.wide.center.aligned.middle.aligned.column.article-answers
                      {:class answer-class}
                      [:div.ui.middle.aligned.grid>div.row>div.column
                       [answer-cell las answer-class]]]])]])))
           (doall))]]))))

(defn- article-list-article-view []
  (when-let [article-id @(subscribe [:article-list/article-id])]
    (with-loader [[:article article-id]] {}
      (let [label-values @(subscribe [:review/active-labels article-id])
            overall-label-id @(subscribe [:project/overall-label-id])
            user-id @(subscribe [:self/user-id])
            user-status @(subscribe [:article/user-status article-id user-id])
            editing? @(subscribe [:article-list/editing?])
            resolving? @(subscribe [:article-list/resolving?])
            close-article #(nav-scroll-top "/project/articles")
            on-confirm #(if resolving?
                          (dispatch [:scroll-top])
                          (do (dispatch [:scroll-top])
                              (dispatch [:article-list/hide-article])))]
        [:div
         [:div.ui.top.attached.header.segment.middle.aligned.article-info-header
          {:style {:padding "0"}}
          [:a.ui.large.fluid.button
           {:on-click close-article}
           [:i.close.icon]
           "Close"]]
         [:div.ui.bottom.attached.middle.aligned.segment
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
                  "Answer missing for a required label"]]])])]]))))

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    (if @(subscribe [:article-list/article-id])
      [article-list-article-view]
      [:div
       [article-filter-form]
       [article-list-view]])))
