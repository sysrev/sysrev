(ns sysrev.views.panels.project.user_labels
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.routes :refer [nav]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [note-content-label updated-time-label]]
   [sysrev.views.labels :as labels]
   [sysrev.views.article :as article]
   [sysrev.views.article-list :refer
    [article-list-filter-form article-list-view article-list-article-view]]
   [sysrev.views.review :as review]
   [sysrev.util :refer [time-from-epoch]]
   [sysrev.shared.util :refer [map-values]]
   [clojure.string :as str])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :user :labels])

(reg-sub
 :user-labels/article-id
 :<- [:active-panel]
 :<- [:article-list/article-id panel]
 (fn [[active-panel article-id]]
   (when (= active-panel panel)
     article-id)))

(reg-sub
 :user-labels/editing?
 :<- [:active-panel]
 :<- [:article-list/editing? panel]
 (fn [[active-panel editing?]]
   (when (= active-panel panel)
     editing?)))

(reg-event-fx
 :user-labels/show-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch [:article-list/show-article article-id panel]}))

(reg-event-fx
 :user-labels/hide-article
 [trim-v]
 (fn []
   {:dispatch [:article-list/hide-article panel]}))

(defn- user-article-filter-form []
  [article-list-filter-form panel])

(defn- user-article-view [article-id]
  [article-list-article-view article-id panel])

(defn user-article-list-entry [article full-size?]
  (let [{:keys [article-id title labels notes updated-time confirmed]} article
        user-id @(subscribe [:self/user-id])]
    [:div.ui.row
     [:div.ui.one.wide.center.aligned.column
      [:div.ui.fluid.labeled.center.aligned.button
       [:i.ui.right.chevron.center.aligned.icon
        {:style {:width "100%"}}]]]
     [:div.ui.fifteen.wide.column.article-title
      [:div.ui.middle.aligned.grid
       [:div.row
        [:div.twelve.wide.column>span.article-title title]
        [:div.four.wide.right.aligned.column
         (when (false? confirmed)
           [:div.ui.tiny.yellow.label "Unconfirmed"])
         (when-let [updated-time (some-> updated-time (time-from-epoch))]
           [updated-time-label updated-time])]]]
      [:div.ui.fitted.divider]
      (let [user-labels
            (->> labels (map-values
                         #(->> %
                               (filter
                                (fn [label]
                                  (= (:user-id label) user-id)))
                               first)))]
        [labels/label-values-component user-labels])
      (when (some #(and (string? %)
                        (not-empty (str/trim %)))
                  (vals notes))
        [:div
         [:div.ui.fitted.divider]
         (doall
          (for [note-key (keys notes)]
            ^{:key [note-key]}
            [note-content-label note-key (get notes note-key)]))])]]))

(defn- user-article-list-view []
  [article-list-view user-article-list-entry panel])

(defmethod panel-content [:project :user] []
  (fn [child]
    child))

(defmethod panel-content [:project :user :labels] []
  (fn [child]
    (when-let [user-id @(subscribe [:self/user-id])]
      [:div
       (with-loader [[:project]
                     [:member/articles user-id]] {}
         [:div
          [user-article-filter-form]
          (if-let [article-id @(subscribe [:user-labels/article-id])]
            [user-article-view article-id]
            [user-article-list-view])])
       child])))
