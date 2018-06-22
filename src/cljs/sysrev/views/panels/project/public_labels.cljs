(ns sysrev.views.panels.project.public-labels
  (:require [clojure.string :as str]
            [cljs-time.coerce :as tc]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :refer
             [selection-dropdown with-ui-help-tooltip ui-help-icon updated-time-label]]
            [sysrev.views.article :refer [article-info-view]]
            [sysrev.views.article-list :as al]
            [sysrev.shared.article-list :refer
             [is-resolved? is-consistent? is-single? is-conflict?]]
            [sysrev.state.ui :refer [get-panel-field]]
            [sysrev.nav :refer [nav nav-scroll-top]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.util :refer [nbsp full-size? number-to-word time-from-epoch]]
            [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :project :articles])

(defmethod al/panel-base-uri panel
  [_ project-id]
  (project-uri project-id "/articles"))

(defmethod al/article-uri panel
  [_ project-id article-id]
  (project-uri project-id (str "/articles/" article-id)))

(reg-sub
 ::default-filters
 (fn [] [(subscribe [:project/overall-label-id])])
 (fn [[overall-id]] {:label-id overall-id}))

(defmethod al/default-filters-sub panel []
  [::default-filters])

(defmethod al/allow-null-label? panel []
  false)

(defmethod al/list-header-tooltip panel []
  ["List of all project articles"])

(defmethod al/private-article-view? panel []
  false)

(defmethod al/loading-articles? panel [_ project-id args]
  @(subscribe [:any-loading? :project/article-list]))

(defmethod al/reload-articles panel [_ project-id args]
  (dispatch [:reload [:project/article-list project-id args]]))

(defmethod al/auto-refresh? panel []
  false)

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

(defmethod al/render-article-entry panel
  [_ article full-size?]
  (let [ ;; label-id @(subscribe [:article-list/filter-value :label-id panel])
        overall-id @(subscribe [:project/overall-label-id])
        {:keys [article-id primary-title labels updated-time]} article
        ;; active-labels (get labels label-id)
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
            [:i.ui.right.chevron.center.aligned.icon
             {:style {:width "100%"}}]]]
          [:div.thirteen.wide.column>span.article-title primary-title]
          [:div.two.wide.center.aligned.column.article-updated-time
           (when-let [updated-time (some-> updated-time (time-from-epoch))]
             [updated-time-label updated-time])]]]]
       [:div.ui.three.wide.center.aligned.middle.aligned.column.article-answers
        {:class answer-class}
        [:div.ui.middle.aligned.grid>div.row>div.column
         [answer-cell article-id overall-labels answer-class]]]]
      ;; mobile view
      [:div.ui.row
       [:div.ui.ten.wide.column.article-title
        [:span.article-title primary-title]
        (when-let [updated-time (some-> updated-time (time-from-epoch))]
          [updated-time-label updated-time])]
       [:div.ui.six.wide.center.aligned.middle.aligned.column.article-answers
        {:class answer-class}
        [:div.ui.middle.aligned.grid>div.row>div.column
         [answer-cell article-id overall-labels answer-class]]]])))

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

(reg-sub
 :public-labels/resolving?
 :<- [:active-panel]
 :<- [:article-list/resolving? panel]
 (fn [[active-panel resolving?]]
   (when (= active-panel panel)
     resolving?)))

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

(reg-event-fx
 :public-labels/set-inclusion-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:article-list/set-filter-value :inclusion-status status panel]}))

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      [:div.project-content
       [al/article-list-view panel]
       child])))
