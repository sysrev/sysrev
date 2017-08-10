(ns sysrev.views.panels.project.user_labels
  (:require
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.routes :refer [nav]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [note-content-label]]
   [sysrev.views.labels :as labels]
   [sysrev.views.article :as article]
   [sysrev.views.review :as review]
   [sysrev.shared.util :refer [map-values]]
   [clojure.string :as str])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel-name [:project :user :labels])

(def ^:private display-count 10)

(reg-sub
 ::articles
 :<- [:member/articles]
 (fn [articles]
   (->> (keys articles)
        (mapv (fn [article-id]
                (let [article (get articles article-id)]
                  (merge article {:article-id article-id}))))
        (sort-by :updated-time >))))

(reg-event-fx
 :user-labels/show-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch-n
    (list [:set-panel-field [:article-id] article-id panel-name]
          [:set-panel-field [:selected-article-id] article-id panel-name])}))
(reg-event-fx
 :user-labels/hide-article
 [trim-v]
 (fn []
   {:dispatch [:set-panel-field [:article-id] nil panel-name]}))
(reg-sub
 :user-labels/article-id
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
 :<- [:user-labels/article-id]
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
 :<- [:user-labels/article-id]
 :<- [::articles]
 (fn [[article-id articles]]
   (when (some #(= (:article-id %) article-id) articles)
     (->> articles
          (take-while #(not= (:article-id %) article-id))
          last
          :article-id))))

(reg-sub-raw
 :user-labels/editing?
 (fn []
   (reaction
    (boolean
     (let [article-id @(subscribe [:user-labels/article-id])
           user-id @(subscribe [:self/user-id])]
       (when (and article-id user-id)
         (let [user-status @(subscribe [:article/user-status article-id user-id])
               review-status @(subscribe [:article/review-status article-id])
               resolving? (and (= review-status "conflict")
                               @(subscribe [:member/resolver? user-id]))]
           (or (= user-status :unconfirmed) resolving?))))))))

(reg-sub-raw
 :user-labels/resolving?
 (fn []
   (reaction
    (boolean
     (when @(subscribe [:user-labels/editing?])
       (let [article-id @(subscribe [:user-labels/article-id])
             user-id @(subscribe [:self/user-id])]
         (when (and article-id user-id)
           (let [review-status @(subscribe [:article/review-status article-id])]
             (and (= review-status "conflict")
                  @(subscribe [:member/resolver? user-id]))))))))))

(defn- user-article-filter-form []
  (let [articles @(subscribe [::articles])]
    [:div.ui.secondary.segment.article-filters]))

(defn- user-article-view [article-id]
  (let [articles @(subscribe [::articles])
        label-values @(subscribe [:review/active-labels article-id])
        overall-label-id @(subscribe [:project/overall-label-id])
        user-id @(subscribe [:self/user-id])
        user-status @(subscribe [:article/user-status article-id user-id])
        editing? @(subscribe [:user-labels/editing?])
        resolving? @(subscribe [:user-labels/resolving?])
        close-article #(nav "/project/user")
        next-id @(subscribe [::next-article-id])
        prev-id @(subscribe [::prev-article-id])
        on-next #(when next-id
                   (nav (str "/project/user/article/" next-id)))
        on-prev #(when prev-id
                   (nav (str "/project/user/article/" prev-id)))
        ;; on-confirm #(dispatch [:user-labels/hide-article])
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
      [:div
       [article/article-info-view article-id :show-labels? false]
       (when editing?
         [:div {:style {:margin-top "1em"}}
          [review/label-editor-view article-id]])]]]))

(reg-sub
 ::display-offset
 (fn []
   [(subscribe [:panel-field [:display-offset] panel-name])])
 (fn [[offset]] (or offset 0)))

(reg-sub
 ::max-display-offset
 :<- [::articles]
 (fn [articles]
   (* display-count (quot (count articles) display-count))))

(reg-event-fx
 ::set-display-offset
 [trim-v]
 (fn [{:keys [db]} [new-offset]]
   {:dispatch [:set-panel-field [:display-offset] new-offset panel-name]}))

(defn- user-article-list-view []
  (let [user-id @(subscribe [:self/user-id])
        active-aid @(subscribe [::selected-article-id])
        articles @(subscribe [::articles])
        total-count (count articles)
        display-offset @(subscribe [::display-offset])
        max-display-offset @(subscribe [::max-display-offset])
        visible-count (min display-count total-count)
        on-first #(dispatch [::set-display-offset 0])
        on-last #(dispatch [::set-display-offset max-display-offset])
        on-next
        #(when (< (+ display-offset display-count) total-count)
           (dispatch [::set-display-offset (+ display-offset display-count)]))
        on-previous
        #(when (>= display-offset display-count)
           (dispatch [::set-display-offset (max 0 (- display-offset display-count))]))
        on-show-article
        (fn [article-id]
          #(nav (str "/project/user/article/" article-id)))]
    [:div
     [:div.ui.top.attached.segment
      {:style {:padding "10px"}}
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        [:h5.no-margin
         (str "Showing " (+ display-offset 1)
              "-" (min total-count (+ display-offset display-count))
              " of "
              total-count " matching articles ")]]
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
        (fn [{:keys [article-id title updated-time labels notes]}]
          (let [active? (= article-id active-aid)
                classes (cond-> []
                          active? (conj "active"))
                note-content (:default notes)]
            [:div.article-list-segments
             {:key article-id}
             [:div.ui.middle.aligned.grid.segment.article-list-article
              {:class (if active? "active" "")
               :style {:cursor "pointer"}
               :on-click (on-show-article article-id)}
              [:div.ui.row
               [:div.ui.one.wide.center.aligned.column
                [:div.ui.fluid.labeled.center.aligned.button
                 [:i.ui.right.chevron.center.aligned.icon
                  {:style {:width "100%"}}]]]
               [:div.ui.fifteen.wide.column.article-title
                [:span.article-title title]
                [:div.ui.fitted.divider]
                (let [labels-map (->> labels (group-by :label-id) (map-values first))]
                  [labels/label-values-component labels-map])
                (when (some #(and (string? %)
                                  (not-empty (str/trim %)))
                            (vals notes))
                  [:div
                   [:div.ui.fitted.divider]
                   (doall
                    (for [note-key (keys notes)]
                      ^{:key [note-key]}
                      [note-content-label note-key (get notes note-key)]))])]]]])))
       (doall))]]))

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
