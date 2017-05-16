(ns sysrev.ui.article-list
  (:require
   [reagent.core :as r]
   [sysrev.ui.article :refer
    [article-info-component label-editor-component label-values-component]]
   [sysrev.ajax :as ajax :refer [pull-article-info]]
   [sysrev.util :refer [full-size? nav nav-scroll-top]]
   [sysrev.shared.util :refer [in?]]
   [sysrev.state.core :as st :refer [data current-project-id]]
   [sysrev.base :refer
    [st work-state get-loading-state set-loading-state]]
   [sysrev.state.project :refer [project]]
   [sysrev.state.labels :as labels]
   [sysrev.ui.components :refer
    [selection-dropdown with-tooltip]]
   [sysrev.routes :as routes :refer [schedule-scroll-top]]
   [clojure.string :as str]
   [goog.string :refer [unescapeEntities]]
   [sysrev.state.project :as project])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(def answer-types
  [:conflict :single :consistent :resolved])

(defprotocol Conflictable
  (is-resolved? [this])
  (is-concordance? [this])
  (is-discordance? [this])
  (is-single? [this])
  (resolution [this]))

(defprotocol IdGroupable
  (id-grouped [this]))

(defprotocol AnswerGroupable
  (label-grouped [this]))

(defprotocol Groupable
  (num-groups [this]))

(defprotocol UserGroupable
  (user-grouped [this]))

(defrecord UserGrouping [user-id article-label])

(defrecord ArticleLabel [article-id primary-title user-id answer])

(defrecord LabelGrouping [answer articles]
  Groupable
  (num-groups [this] (count articles)))

(defrecord IdGrouping [article-id article-labels]
  Groupable
  (num-groups [this] (count article-labels))
  AnswerGroupable
  (label-grouped [this]
    (->> article-labels
         (filterv
          (fn [{:keys [answer]}]
            (not (or (nil? answer)
                     (and (coll? answer) (empty? answer))))))
         (group-by :answer)
         (mapv (fn [[k v]] (->LabelGrouping k v)))))
  UserGroupable
  (user-grouped [this]
    (mapv (fn [[k v]] (->UserGrouping k (first v)))
          (group-by :user-id article-labels)))
  Conflictable
  (is-resolved? [this]
    (->> article-labels (mapv :resolve) (filterv identity) first))
  (resolution [this]
    (->> article-labels (filterv :resolve) first))
  (is-discordance? [this]
    (and (not (is-resolved? this))
         (-> (label-grouped this) (keys) (count) (> 1))))
  (is-single? [this]
    (= 1 (num-groups this)))
  (is-concordance? [this]
    (and (not (is-resolved? this))
         (-> (label-grouped this) (count) (= 1) (and (not (is-single? this)))))))

(defrecord ArticleLabels [articles]
  IdGroupable
  (id-grouped [this]
    (mapv (fn [[k v]] (->IdGrouping k v))
          (group-by :article-id articles))))

(defn search-box [current-value value-changed]
  (let [update-value #(-> % .-target .-value value-changed)]
    [:div.ui.field
     [:div.ui.left.icon.input
      [:input {:placeholder "Search..."
               :on-change update-value
               :value current-value}]
      [:i.search.icon]]]))

(defn reset-display-offset []
  (swap! work-state assoc-in [:page :articles :display-offset] 0))
(defn selected-article-id []
  (st :page :articles :article-id))
(defn toggle-article [article-id]
  (swap! work-state assoc-in
         [:page :articles :article-id] article-id))

(defn select-label [label-id]
  (swap! work-state
         (comp
          #(assoc-in % [:page :articles :label-id] label-id)
          #(assoc-in % [:page :articles :answer-value] nil)))
  (reset-display-offset))
(defn selected-label-id []
  (or (st :page :articles :label-id)
      (project :overall-label-id)))

(defn select-answer-status [status]
  (swap! work-state assoc-in
         [:page :articles :answer-status] status)
  (reset-display-offset))
(defn selected-answer-status []
  (st :page :articles :answer-status))
(defn answer-status-filter []
  (case (selected-answer-status)
    :conflict is-discordance?
    :resolved is-resolved?
    :consistent is-concordance?
    :single is-single?
    #(do true)))

(defn selected-answer-value []
  (st :page :articles :answer-value))
(defn select-answer-value [value]
  (swap! work-state assoc-in
         [:page :articles :answer-value] value)
  (reset-display-offset))
(defn answer-value-filter []
  (let [active (selected-answer-value)]
    (if (nil? active)
      #(do true)
      (fn [article-group]
        (let [answers (->> article-group
                           :article-labels
                           (mapv :answer)
                           (mapv #(if (sequential? %) % [%]))
                           (apply concat)
                           distinct)]
          (in? answers active))))))

(defn label-selector []
  (let [active-id (selected-label-id)
        active-label (project :labels active-id)]
    [selection-dropdown
     [:div.text (:short-label active-label)]
     (->> (labels/project-labels-ordered)
          (mapv
           (fn [{:keys [label-id name short-label]}]
             [:div.item
              (into {:key label-id
                     :on-click #(select-label label-id)}
                    (when (= label-id active-id)
                      {:class "active selected"}))
              short-label])))]))

(defn answer-status-selector []
  (let [active-status (selected-answer-status)
        status-name #(if %
                       (-> % name str/capitalize)
                       "<Any>")]
    [selection-dropdown
     [:div.text (status-name active-status)]
     (->> (concat [nil] answer-types)
          (mapv
           (fn [status]
             [:div.item
              (into {:key (str status)
                     :on-click #(select-answer-status status)}
                    (when (= status active-status)
                      {:class "active selected"}))
              (status-name status)])))]))

(defn answer-value-selector []
  (when-let [active-id (selected-label-id)]
    (let [active-label (project :labels active-id)
          all-values (labels/label-possible-values active-id)
          active-value (selected-answer-value)]
      [selection-dropdown
       [:div.text
        (if (nil? active-value) "<Any>" (str active-value))]
       (vec
        (concat
         [[:div.item {:on-click #(select-answer-value nil)} "<Any>"]]
         (->> all-values
              (mapv
               (fn [value]
                 [:div.item
                  (into {:key (str value)
                         :on-click #(select-answer-value value)}
                        (when (= value active-value)
                          {:class "active selected"}))
                  (str value)])))))])))

(defn article-filter-form []
  (let [label-id (selected-label-id)]
    [:div.ui.secondary.segment
     {:style {:padding "10px"}}
     [:div.ui.small.dividing.header "Article filters"]
     [:form.ui.form
      #_ [search-box search-value search-update]
      [:div.field
       [:div.fields
        [:div.ui.small.three.wide.field
         [:label "Label"]
         [label-selector]]
        [:div.ui.small.three.wide.field
         [:label "Answer status"]
         [answer-status-selector]]
        (when (and label-id
                   (not-empty (labels/label-possible-values label-id)))
          [:div.ui.small.three.wide.field
           [:label "Answer value"]
           [answer-value-selector]])]]]]))

(defmulti answer-cell-icon identity)
(defmethod answer-cell-icon true [] [:i.ui.green.circle.plus.icon])
(defmethod answer-cell-icon false [] [:i.ui.orange.circle.minus.icon])
(defmethod answer-cell-icon :default [] [:i.ui.grey.question.mark.icon])

(defn answer-cell [label-groups answer-class]
  [:div.ui.divided.list
   (->> (user-grouped label-groups)
        (map (fn [u]
               (let [user-id (:user-id u)
                     article-label (:article-label u)
                     article-id (:article-id article-label)
                     answer (:answer article-label)
                     key (str "discord-" user-id "-" article-id)]
                 (when (or (not= answer-class "resolved")
                           (:resolve article-label))
                   [:div.item {:key key}
                    (answer-cell-icon answer)
                    [:div.content>div.header
                     (st/user-name-by-id user-id)]]))))
        (doall))])

(defn article-list-view [id-grouped-articles]
  (let [display-count 10
        total-count (count id-grouped-articles)
        display-offset (or (st :page :articles :display-offset) 0)
        visible-count (min display-count total-count)
        selected-label (project :labels (selected-label-id))
        active-aid (selected-article-id)
        on-next
        #(when (< (+ display-offset display-count) total-count)
           (swap! work-state assoc-in
                  [:page :articles :display-offset]
                  (+ display-offset display-count)))
        on-previous
        #(when (>= display-offset display-count)
           (swap! work-state assoc-in
                  [:page :articles :display-offset]
                  (- display-offset display-count)))]
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
       (->> id-grouped-articles
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
                  (is-resolved? las) "resolved"
                  (is-concordance? las) "consistent"
                  (is-single? las) "single"
                  :else "conflict")
                classes
                (cond-> []
                  active? (conj "active"))]
            [:div.article-list-segments
             {:key article-id}
             [:div.ui.middle.aligned.grid.segment.article-list-article
              {:class (if active? "active" "")
               :style {:cursor "pointer"}
               :on-click #(do (set-loading-state [:article-list article-id])
                              (nav (str "/project/articles/" article-id))
                              (toggle-article article-id))}
              [:div.ui.inverted.dimmer
               {:class (if (get-loading-state [:article-list article-id])
                         "active" "")}
               [:div.ui.small.loader]]
              (if (full-size?)
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
       (doall))]]))

(defn article-list-article-view [article-id]
  (let [label-values (labels/active-label-values article-id)
        overall-label-id (project :overall-label-id)
        user-id (st/current-user-id)
        status (labels/user-article-status article-id)
        review-status (project/article-review-status article-id)
        resolving? (and (= review-status "conflict")
                        (project/project-resolver? user-id))
        close-article #(if-let [prev (st :page :articles :prev-uri)]
                         (nav-scroll-top prev)
                         (do (nav "/project/articles")
                             (using-work-state
                              (ajax/fetch-data
                               [:label-activity (selected-label-id)] true))))
        on-confirm #(if resolving?
                      (do (set-loading-state :confirm false)
                          (schedule-scroll-top))
                      (close-article))]
    [:div
     [:div.ui.top.attached.header.segment.middle.aligned.article-info-header
      {:style {:padding "0"}}
      [:a.ui.large.fluid.button
       {:on-click close-article}
       [:i.close.icon]
       "Close"]]
     [:div.ui.bottom.attached.middle.aligned.segment
      (let [show-labels (if (= status :unconfirmed) false :all)]
        [article-info-component article-id show-labels user-id review-status])
      (when (or (= status :unconfirmed) resolving?)
        [:div {:style {:margin-top "1em"}}
         [label-editor-component]
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
              "Answer missing for a required label"]]])])]]))

(defn articles-page []
  (let [filtered-articles
        (->> (data [:label-activity (selected-label-id)])
             (mapv map->ArticleLabel)
             (->ArticleLabels)
             (id-grouped)
             (filterv (answer-status-filter))
             (filterv (answer-value-filter)))]
    (if (st/modal-article-id)
      [:div
       [article-list-article-view (st/modal-article-id)]]
      [:div
       [article-filter-form]
       [article-list-view filtered-articles]])))
