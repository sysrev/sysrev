(ns sysrev.ui.article-list
  (:require [reagent.core :as r]
            [sysrev.ui.article :refer
             [article-info-component label-editor-component]]
            [sysrev.ajax :as ajax :refer [pull-article-info]]
            [sysrev.util :refer [nav full-size?]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.state.core :as st :refer [data current-project-id]]
            [sysrev.base :refer
             [st work-state get-loading-state set-loading-state]]
            [sysrev.state.project :refer [project]]
            [sysrev.state.labels :as labels]
            [sysrev.ui.components :refer
             [selection-dropdown with-tooltip]]
            [sysrev.routes :as routes]
            [clojure.string :as str]
            [goog.string :refer [unescapeEntities]])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(def answer-types
  [:conflict :single :consistent :resolved])

(defn user-name-by-id [id]
  (let [user (data [:users id])]
    (or (:name user)
        (-> (:email user) (str/split "@") first))))

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
    (->> article-labels (mapv :resolves) (filterv identity) first))
  (resolution [this]
    (->> article-labels (filterv :resolves) first))
  (is-discordance? [this]
    (-> (label-grouped this) (keys) (count) (> 1)))
  (is-single? [this]
    (= 1 (num-groups this)))
  (is-concordance? [this]
    (-> (label-grouped this) (count) (= 1) (and (not (is-single? this))))))

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

(defn selected-article-id []
  (st :page :articles :article-id))
(defn toggle-article [article-id]
  (if (= article-id (selected-article-id))
    (swap! work-state assoc-in
           [:page :articles :article-id] nil)
    (do (pull-article-info article-id)
        (swap! work-state assoc-in
               [:page :articles :article-id] article-id))))

(defn select-label [label-id]
  (swap! work-state
         (comp
          #(assoc-in % [:page :articles :label-id] label-id)
          #(assoc-in % [:page :articles :answer-value] nil))))
(defn selected-label-id []
  (or (st :page :articles :label-id)
      (project :overall-label-id)))

(defn select-answer-status [status]
  (swap! work-state assoc-in
         [:page :articles :answer-status] status))
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
         [:page :articles :answer-value] value))
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

(defn answer-cell [label-groups]
  [:div.ui.divided.list
   (->> (user-grouped label-groups)
        (map (fn [u]
               (let [user-id (:user-id u)
                     article-label (:article-label u)
                     article-id (:article-id article-label)
                     answer (:answer article-label)
                     key (str "discord-" user-id "-" article-id)]
                 [:div.item {:key key}
                  (answer-cell-icon answer)
                  [:div.content>div.header
                   (user-name-by-id user-id)]])))
        (doall))])

(defn article-labels-view [article-id]
  (let [answers (data [:article-labels article-id])
        user-ids (sort (keys answers))
        label-ids-answered (->> (vals answers)
                                (map keys)
                                (apply concat)
                                distinct)
        label-ids
        (->> (labels/project-labels-ordered)
             (map :label-id)
             (filter (in? label-ids-answered))
             (filter
              (fn [label-id]
                (some
                 (fn [user-id]
                   (let [answer (get-in answers [user-id label-id])]
                     (not (or (nil? answer)
                              (and (coll? answer)
                                   (empty? answer))))))
                 user-ids))))]
    [:div
     [:table.ui.top.attached.table.user-labels
      [:thead
       [:tr {:style {:font-weight "bold"}}
        [:th "Label"]
        (doall
         (for [user-id user-ids]
           (let [user-name (user-name-by-id user-id)]
             [:th {:key user-id} user-name])))]]
      [:tbody
       (doall
        (for [label-id label-ids]
          (let [{:keys [short-label value-type]}
                (project :labels label-id)]
            [:tr {:key label-id}
             [:td short-label]
             (doall
              (for [user-id user-ids]
                [:td {:key [label-id user-id]}
                 (let [answer (get-in answers [user-id label-id])
                       values (case value-type
                                "boolean" (if (boolean? answer)
                                            [answer] [])
                                "categorical" answer
                                "numeric" answer
                                "string" answer)]
                   (str/join ", " values))]))])))]]
     [:div.ui.bottom.attached.segment
      {:style {:padding "0"}}
      [:a.ui.small.fluid.button
       {:href (str "/article/" article-id)
        ;; :on-click #(swap! work-state (st/set-modal-article-id article-id))
        :style {:border-radius "0"}}
       "Go to article"]]]))

(defn article-list-view [id-grouped-articles]
  (let [max-count 30
        total-count (count id-grouped-articles)
        visible-count (min max-count total-count)
        selected-label (project :labels (selected-label-id))]
    [:div
     [:div.ui.top.attached.segment
      {:style {:padding "10px"}}
      [:h5 (str "Showing " visible-count " of "
                total-count " matching articles")]]
     [:div.ui.bottom.attached.segment.article-list-segment
      (->>
       (take max-count id-grouped-articles)
       (map
        (fn [las]
          (let [article-id (:article-id las)
                fla (first (:article-labels las))
                title (:primary-title fla)
                active? (= article-id (selected-article-id))
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
             [:div.ui.top.attached.middle.aligned.grid.segment.article-list-article
              {:class (if active? "active" "")
               :style {:cursor "pointer"}
               :on-click #(toggle-article article-id)}
              (if (full-size?)
                [:div.ui.row
                 [:div.ui.twelve.wide.column.article-title
                  [:span.article-title title]]
                 [:div.ui.four.wide.center.aligned.middle.aligned.column.article-answers
                  {:class answer-class}
                  [:div.ui.middle.aligned.grid>div.row>div.column
                   [answer-cell las]]]]
                [:div.ui.row
                 [:div.ui.ten.wide.column.article-title
                  [:span.article-title title]]
                 [:div.ui.six.wide.center.aligned.middle.aligned.column.article-answers
                  {:class answer-class}
                  [:div.ui.middle.aligned.grid>div.row>div.column
                   [answer-cell las]]]])]
             [:div
              {:class (if active?
                        "ui attached segment"
                        "ui bottom attached segment")
               :style {:padding "0"}
               :on-click #(toggle-article article-id)}
              [:div.ui.small.fluid.button.fluid-bottom
               (if active?
                 [:i.ui.up.chevron.icon]
                 [:i.ui.down.chevron.icon])]]
             (when active?
               [:div.ui.bottom.attached.grid.segment.article-list-expanded
                [:div.ui.row
                 [:div.ui.sixteen.wide.column
                  [article-labels-view article-id]]]])])))
       (doall))]]))

(defn article-list-article-view [article-id]
  (let [label-values (labels/active-label-values article-id)
        overall-label-id (project :overall-label-id)
        user-id (st/current-user-id)
        status (labels/user-article-status article-id)]
    [:div
     [article-info-component article-id false]
     [label-editor-component]
     #_ [confirm-modal-box #(schedule-scroll-top)]
     (let [missing (labels/required-answers-missing label-values)
           disabled? ((comp not empty?) missing)
           confirm-button
           [:div.ui.primary.right.labeled.icon.button
            {:class (str (if disabled? "disabled" "")
                         " "
                         (if (get-loading-state :confirm) "loading" ""))
             :on-click
             (fn []
               (set-loading-state :confirm true)
               (ajax/confirm-active-labels))}
            "Confirm labels"
            [:i.check.circle.outline.icon]]]
       [:div.ui.grid.centered
        [:div.row
         (if disabled?
           [with-tooltip [:div confirm-button]]
           confirm-button)
         [:div.ui.inverted.popup.top.left.transition.hidden
          "Answer missing for a required label"]]])]))

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
