(ns sysrev.views.panels.project.public_labels
  (:require
   [clojure.string :as str]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer
    [selection-dropdown with-ui-help-tooltip ui-help-icon updated-time-label]]
   [sysrev.views.article :refer [article-info-view]]
   [sysrev.views.review :refer [label-editor-view]]
   [sysrev.views.article-list :as al]
   [sysrev.subs.ui :refer [get-panel-field]]
   [sysrev.routes :refer [nav nav-scroll-top]]
   [sysrev.util :refer [nbsp full-size? number-to-word time-from-epoch]]
   [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :project :articles])

(reg-sub
 :public-labels/article-id
 :<- [:active-panel]
 :<- [:article-list/article-id panel]
 (fn [[active-panel article-id]]
   (when (= active-panel panel)
     article-id)))

(reg-sub
 :public-labels/editing?
 :<- [:active-panel]
 :<- [:article-list/editing? panel]
 (fn [[active-panel editing?]]
   (when (= active-panel panel)
     editing?)))

(reg-event-fx
 :public-labels/show-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch [:article-list/show-article article-id panel]}))

(reg-event-fx
 :public-labels/hide-article
 [trim-v]
 (fn []
   {:dispatch [:article-list/hide-article panel]}))

(reg-event-fx
 :public-labels/reset-filters
 [trim-v]
 (fn [_ [keep]]
   {:dispatch [::al/reset-filters keep panel]}))

(reg-event-fx
 :public-labels/set-group-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:article-list/set-filter-value :group-status status panel]}))

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

(defn public-labels-article-entry [article full-size?]
  (let [label-id @(subscribe [:article-list/filter-value :label-id panel])
        {:keys [article-id title labels updated-time]} article
        labels (get labels label-id)
        answer-class
        (cond
          (al/is-resolved? labels) "resolved"
          (al/is-consistent? labels) "consistent"
          (al/is-single? labels) "single"
          :else "conflict")]
    (if full-size?
      [:div.ui.row
       [:div.ui.thirteen.wide.column.article-title
        [:div.ui.middle.aligned.grid
         [:div.row
          [:div.ui.one.wide.center.aligned.column
           [:div.ui.fluid.labeled.center.aligned.button
            [:i.ui.right.chevron.center.aligned.icon
             {:style {:width "100%"}}]]]
          [:div.thirteen.wide.column>span.article-title title]
          [:div.two.wide.center.aligned.column.article-updated-time
           (when-let [updated-time (some-> updated-time (time-from-epoch))]
             [updated-time-label updated-time])]]]]
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
         [answer-cell article-id labels answer-class]]]])))

(defn- public-labels-filter-form []
  [al/article-list-filter-form panel])

(defn- public-labels-list-view []
  [al/article-list-view public-labels-article-entry panel])

(defn- public-labels-article-view []
  (when-let [article-id @(subscribe [:public-labels/article-id])]
    [al/article-list-article-view article-id panel]))

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    [:div
     [public-labels-filter-form]
     (with-loader [[:project/public-labels]] {}
       (if @(subscribe [:public-labels/article-id])
         [public-labels-article-view]
         [public-labels-list-view]))]))
