(ns sysrev.views.panels.project.user-labels
  (:require
   [clojure.string :as str]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.nav :refer [nav]]
   [sysrev.state.nav :refer [project-uri]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [note-content-label updated-time-label]]
   [sysrev.views.labels :as labels]
   [sysrev.views.article :as article]
   [sysrev.views.article-list :as al]
   [sysrev.views.review :as review]
   [sysrev.util :refer [time-from-epoch]]
   [sysrev.shared.util :refer [map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :user :labels])

(defmethod al/panel-base-uri panel
  [_ project-id]
  (project-uri project-id "/user"))

(defmethod al/article-uri panel [_ project-id article-id]
  (project-uri project-id (str "/user/article/" article-id)))

(defmethod al/all-articles-sub panel []
  [:member/articles])

(reg-sub
 ::default-filters
 (fn []
   {:label-id nil}))

(defmethod al/default-filters-sub panel []
  [::default-filters])

(defmethod al/allow-null-label? panel []
  true)

(defmethod al/private-article-view? panel []
  true)

(defmethod al/loading-articles? panel [_ project-id user-id]
  @(subscribe [:loading? [:member/articles project-id user-id]]))

(defmethod al/reload-articles panel [_ project-id user-id]
  (dispatch [:reload [:member/articles project-id user-id]]))

(defmethod al/auto-refresh? panel []
  true)

(defmethod al/render-article-entry panel
  [_ article full-size?]
  (let [{:keys [article-id title labels notes updated-time confirmed]} article
        user-id @(subscribe [:self/user-id])
        have-notes? (some #(and (string? %)
                                (not-empty (str/trim %)))
                          (vals notes))]
    (if full-size?
      ;; non-mobile view
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
             [:div.ui.tiny.basic.yellow.label "Unconfirmed"])
           (when-let [updated-time (some-> updated-time (time-from-epoch))]
             [updated-time-label updated-time])]]]
        [:div.ui.fitted.divider]
        [:div.ui.middle.aligned.grid
         [:div.row
          [:div.sixteen.wide.column
           (let [user-labels
                 (->> labels (map-values
                              #(->> %
                                    (filter
                                     (fn [label]
                                       (= (:user-id label) user-id)))
                                    first)))]
             [labels/label-values-component user-labels])]]]
        (when have-notes?
          [:div
           [:div.ui.fitted.divider]
           (doall
            (for [note-name (keys notes)]
              ^{:key [note-name]}
              [note-content-label note-name (get notes note-name)]))])]]
      ;; mobile view
      [:div.ui.row.user-article
       [:div.ui.sixteen.wide.column
        [:div.ui.middle.aligned.grid
         [:div.row
          [:div.twelve.wide.column>span.article-title title]
          [:div.four.wide.right.aligned.column
           (when (false? confirmed)
             [:div.ui.tiny.basic.yellow.label "Unconfirmed"])
           (when-let [updated-time (some-> updated-time (time-from-epoch))]
             [updated-time-label updated-time])]]]
        [:div.ui.fitted.divider]
        [:div.ui.middle.aligned.grid
         [:div.row
          [:div.sixteen.wide.column.label-values
           (let [user-labels
                 (->> labels (map-values
                              #(->> %
                                    (filter
                                     (fn [label]
                                       (= (:user-id label) user-id)))
                                    first)))]
             [labels/label-values-component user-labels])]]]
        (when have-notes?
          [:div.ui.fitted.divider])
        (when have-notes?
          [:div.ui.middle.aligned.grid
           [:div.row
            [:div.sixteen.wide.column.label-values
             (doall
              (for [note-name (keys notes)]
                ^{:key [note-name]}
                [note-content-label note-name (get notes note-name)]))]]])]])))

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

(defmethod panel-content [:project :user] []
  (fn [child]
    child))

(defmethod panel-content [:project :user :labels] []
  (fn [child]
    (let [user-id @(subscribe [:self/user-id])
          project-id @(subscribe [:active-project-id])]
      (when (and user-id project-id)
        [:div.project-content
         [al/article-list-view panel [[:member/articles project-id user-id]]]
         child]))))
