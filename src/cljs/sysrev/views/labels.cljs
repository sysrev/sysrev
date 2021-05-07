(ns sysrev.views.labels
  (:require [clojure.string :as str]
            [medley.core :as medley]
            [re-frame.core :refer [subscribe dispatch]]
            [cljs-time.core :as t]
            [reagent.core :as r]
            [sysrev.views.components.core :as ui
             :refer [updated-time-label note-content-label]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.annotator :as ann]
            [sysrev.views.semantic :refer [Table TableHeader TableHeaderCell TableRow TableBody
                                           TableCell Icon Button]]
            [sysrev.state.label :refer [real-answer?]]
            [sysrev.util :as util :refer [in? css time-from-epoch]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn FilterSelector [{:keys [on-remove on-select options selected title]}]
  [:div.ui.small.form
   [:div.field>div.fields
    [:div.eight.wide.field
     [:div {:style {:margin-bottom "4px"}}
      [:b title]]
     (map #(-> [:div {:class "ui label"
                      :key %
                      :on-click (util/wrap-user-event
                                 (fn [] (on-remove (str/lower-case %))))}
                %
                [:i {:class "delete icon"}]])
          selected)
     (when (seq options)
       [:div {:style {:margin-top "4px"}}
        [ui/selection-dropdown
         [:div.text "—"]
         (map #(-> [:div.item {:key %} %]) options)
         {:onChange #(on-select (str/lower-case %2))}]])]]])

(defn FilterElement
  "Requires a seq of unique, non-blank string options."
  [{:keys [class filters on-change on-close options]
    :or {on-change #(-> nil)}}]
  (let [{:keys [exclude match]} filters
        selected (-> #{} (into exclude) (into match))
        match-options (remove #(contains? selected (str/lower-case %))
                              options)
        exclude-options (remove #(contains? selected (str/lower-case %))
                                options)]
    [:div.ui.secondary.segment.edit-filter {:class class}
     (when (or (seq match) (seq match-options))
       [FilterSelector {:on-remove
                        #(on-change
                          (update filters :match
                                  (partial remove (partial contains? #{%}))))
                        :on-select
                        #(on-change (update filters :match conj %))
                        :options match-options
                        :selected match
                        :title "Match"}])
     (when (or (seq exclude) (seq exclude-options))
       [FilterSelector {:on-remove
                        #(on-change
                          (update filters :exclude
                                  (partial remove (partial contains? #{%}))))
                        :on-select
                        #(on-change filters (update filters :exclude conj %))
                        :options exclude-options
                        :selected exclude
                        :title "Exclude"}]
       [Button {:on-click #(when on-close (apply on-close %&))}
        "Hide"])]))

(defn ValueDisplay [root-label-id label-id answer]
  (let [inclusion @(subscribe [:label/answer-inclusion root-label-id label-id answer])
        color (case inclusion
                true   "green"
                false  "orange"
                nil)
        values (if (= "boolean" @(subscribe [:label/value-type root-label-id label-id]))
                 (if (boolean? answer) [answer] [])
                 (cond (nil? answer)        nil
                       (sequential? answer) answer
                       :else                [answer]))]
    [:span {:class (when color (str color "-text"))}
     (-> (some->> (seq values) (str/join ", "))
         (or "—"))]))

(defn LabelAnswerTag [root-label-id label-id answer]
  (let [display @(subscribe [:label/display root-label-id label-id])
        display-label (if (= "boolean" @(subscribe [:label/value-type root-label-id label-id]))
                        (str display "?")
                        display)
        dark-theme? @(subscribe [:self/dark-theme?])]
    [:div.ui.tiny.labeled.button.label-answer-tag
     [:div.ui.button {:class (when dark-theme? "basic")}
      (str display-label " ")]
     [:div.ui.basic.label
      [ValueDisplay root-label-id label-id answer]]]))

(defn LabelHeaderCell []
  (let [state (r/atom {:open? false})]
    (fn [{:keys [filters group-label-id i label label-display
                 on-change options sort toggle-sort]}]
      (let [{:keys [open?]} @state]
        [TableHeaderCell
         [:div {:on-click toggle-sort
                :style {:cursor "pointer"
                        :width "100%"}}
          [:i {:class (if (->> filters vals (apply concat) seq)
                        "filter icon active"
                        "filter icon")
               :on-click #(do (.stopPropagation %)
                              (swap! state update :open? not))
               :style {:cursor "pointer"
                       :margin "4px"}}]
          label-display
          [:i {:class ({:asc "arrow up icon active"
                        :desc "arrow down icon active"
                        nil "minus icon"}
                       sort)
               :style {:margin-left "8px"}}]]
         [ui/Popper {:anchor-component (r/current-component)
                     :props {:flip {:enabled true}
                             :open open?
                             :placement "top-start"}}
          [FilterElement {:class "detached"
                          :filters filters
                          :on-change on-change
                          :on-close #(swap! state assoc :open? false)
                          :options options}]]]))))

(defn GroupLabelAnswerTable []
  (let [state (r/atom {:filters {} :sorts '()})
        current-sort (fn [sorts i]
                       (some (fn [[j order]] (when (= i j) order)) sorts))
        toggle-sort! (fn [i]
                      (swap! state update :sorts
                             (fn [sorts]
                               (let [current (current-sort sorts i)
                                     sorts (remove #(= i (first %)) sorts)]
                                 (if (= :asc current)
                                   sorts
                                   (if (= :desc current)
                                     (cons [i :asc] sorts)
                                     (cons [i :desc] sorts)))))))
        compare-cols (fn [a b [i order]]
                       (let [av (nth a i)
                             bv (nth b i)
                             c (try (compare av bv)
                                    (catch js/Error _e
                                        0))]
                         (({:asc identity :desc -} order) c)))
        compare-rows (fn [a b sorts]
                       (reduce
                        #(if (zero? %)
                           (compare-cols a b %2)
                           (reduced %))
                        0
                        sorts))
        value-matches? (fn [v match exclude]
                         (and
                          (or (empty? match) (some #(= v %) match))
                          (or (empty? exclude) (not (some #(= v %) exclude)))))
        row-matches? (fn [row value-types [i {:keys [exclude match]}]]
                       (let [v (nth row i)
                             value-type (nth value-types i)
                             v (case value-type
                                 "boolean" ({true "true"
                                             false "false"
                                             nil "—"}
                                            v)
                                 "categorical" (when (seq v)
                                                 (map str/lower-case v))
                                 "string" (if (empty? v) "—" (str/lower-case v))
                                 v)]
                         (if (= "categorical" value-type)
                           (if (empty? v)
                             (value-matches? "—" match exclude)
                             (and (some #(value-matches? % match nil) v)
                                  (every? #(value-matches? % nil exclude) v)))
                           (value-matches? v match exclude))))
        filter-rows (fn [filters value-types rows]
                      (filter
                       #(every? (partial row-matches? % value-types) filters)
                       rows))
        label-values (fn [i label rows]
                       (case (:value-type label)
                         "boolean" ["—" "True" "False"]
                         "categorical" (->> label :definition :all-values
                                            (concat
                                             (when (some #(empty? (nth % i)) rows)
                                               ["—"])))
                         "string" (let [vs (->> rows
                                                (map #(str (nth % i)))
                                                (medley/distinct-by
                                                 str/lower-case))]
                                    (->> vs
                                         (remove empty?)
                                         (sort-by str/lower-case)
                                         ((if (some empty? vs)
                                            (partial cons "—")
                                            identity))))
                         []))]
    (fn [{:keys [group-label-id indexed? label-name labels rows]}]
      (let [{:keys [filters sorts]} @state
            display-rows (->> rows
                              (filter-rows filters (mapv :value-type labels))
                              (sort #(compare-rows % %2 sorts)))]
        [Table {:striped true :class "group-label-values-table"}
         [TableHeader {:full-width true}
          [TableRow {:text-align "center"}
           [TableHeaderCell {:col-span (if indexed?
                                         (+ (count labels) 1)
                                         (count labels))} label-name]]]
         [TableHeader
          [TableRow
           (when indexed? [TableHeaderCell])
           (doall
            (map-indexed
             #(-> ^{:key (str group-label-id "-" (:label-id %2) "-table-header")}
                  [LabelHeaderCell
                   {:group-label-id group-label-id
                    :filters (filters %)
                    :i %
                    :label %2
                    :label-display @(subscribe [:label/display group-label-id (:label-id %2)])
                    :on-change (fn [x]
                                 (swap! state assoc-in [:filters %] x))
                    :options (label-values % %2 rows)
                    :sort (current-sort sorts %)
                    :toggle-sort (fn [] (toggle-sort! %))}])
             labels))]]
         [TableBody
          (for [[i row] (map-indexed vector display-rows)]
            ^{:key (str group-label-id "-" i "-row")}
            [TableRow
             (when indexed? [TableCell (inc i)])
             (for [[j answer] (map-indexed vector row)
                   :let [label-id (:label-id (nth labels j))]]
               ^{:key (str group-label-id "-" i "-row-" label-id "-cell")}
               [TableCell
                [ValueDisplay group-label-id label-id
                 answer]])])]]))))

(defn GroupLabelAnswerTag [{:keys [group-label-id answers indexed?]
                            :or {indexed? false}
                            :as opts}]
  (let [labels (->> (vals @(subscribe [:label/labels "na" group-label-id]))
                    (sort-by :project-ordering <)
                    (filter :enabled))
        label-name @(subscribe [:label/display "na" group-label-id])
        label-ids (mapv :label-id labels)
        answer-value (fn [answer label-id]
                       (let [v (if (contains? answer label-id)
                                 (get answer label-id)
                                 (get answer (keyword (str label-id))))
                             ;; In the articles list view answers keys are
                             ;; keywords, not UUIDs.
                             ]
                         (if (and (string? v) (str/blank? v))
                           nil
                           v)))
        answer->row (fn [answer]
                      (mapv #(answer-value answer %) label-ids))]
    (when (seq answers)
      [:div.ui.tiny.labeled.label-answer-tag.overflow-x-auto
       [GroupLabelAnswerTable
        (-> (dissoc opts :answers)
            (assoc :label-name label-name :labels labels
                   :rows (->> answers vals (map answer->row))))]])))

(defn AnnotationLabelAnswerTag [{:keys [annotation-label-id answer]}]
  (let [label-name @(subscribe [:label/display "na" annotation-label-id])
        entities (->> answer vals (group-by :semantic-class)
                      (map (fn [[entity annotations]]
                             [entity (map :value annotations)])))
        dark-theme? @(subscribe [:self/dark-theme?])]
    [:<>
     (doall
       (for [[entity values] entities]
         [:div.ui.tiny.labeled.button.label-answer-tag {:key (str label-name "-" entity)}
          [:div.ui.button {:class (when dark-theme? "basic")}
           (if entity
             (str label-name "|" entity)
             (str label-name))]
          [:div.ui.basic.label
           (->> values (filter some?) (str/join ", "))]]))]))

(defn LabelValuesView [labels & {:keys [notes user-name resolved?]}]
  (let [all-label-ids (->> @(subscribe [:project/label-ids])
                           (filter #(contains? labels %)))
        value-type #(deref (subscribe [:label/value-type "na" %]))
        dark-theme? @(subscribe [:self/dark-theme?])]
    [:div.label-values
     (when user-name
       [:div.ui.label.user-name {:class (css [(not dark-theme?) "basic"])}
        user-name])
     (doall ;; basic labels
      (for [[label-id answer] (->> all-label-ids
                                   (remove #(or (= "group" (value-type %))
                                                (= "annotation" (value-type %))))
                                   (map #(list % (get-in labels [% :answer]))))]
        (when (real-answer? answer)
          ^{:key (str label-id)} [LabelAnswerTag "na" label-id answer])))
     (doall ;; annotation labels
            (for [[annotation-label-id answer] (->> all-label-ids
                                                    (filter #(= "annotation" (value-type %)))
                                                    (map #(list % (get-in labels [% :answer]))))]
              ^{:key (str annotation-label-id)}
              [AnnotationLabelAnswerTag {:annotation-label-id annotation-label-id
                                         :answer answer}]))
     (doall ;; group labels
      (for [[group-label-id answer] (->> all-label-ids
                                         (filter #(= "group" (value-type %)))
                                         (map #(list % (get-in labels [% :answer]))))]
        ^{:key (str group-label-id)}
        [GroupLabelAnswerTag {:group-label-id group-label-id
                              :answers (:labels answer)}]))
     
     (when (and (some #(contains? % :confirm-time) (vals labels))
                (some #(in? [0 nil] (:confirm-time %)) (vals labels)))
       [:div.ui.basic.yellow.label.labels-status "Unconfirmed"])
     (when resolved?
       [:div.ui.basic.purple.label.labels-status "Resolved"])
     (for [note-name (keys notes)] ^{:key [note-name]}
       [note-content-label note-name (get notes note-name)])] ))

(defn- ArticleLabelValuesView [article-id user-id]
  (let [labels @(subscribe [:article/labels article-id user-id])
        resolved? (= user-id @(subscribe [:article/resolve-user-id article-id]))]
    [LabelValuesView labels :resolved? resolved?]))

(defn- copy-user-answers [project-id article-id user-id]
  (let [label-ids (set @(subscribe [:project/label-ids project-id]))
        labels @(subscribe [:article/labels article-id user-id])
        nil-events (for [label-id label-ids]
                     [:review/set-label-value article-id "na" label-id "na" nil])
        group? #(boolean (get-in % [:answer :labels]))
        group-label-ids     (keys (util/filter-values group? labels))
        non-group-label-ids (keys (util/filter-values (comp not group?) labels))
        group-munge-labels (mapcat (fn [label-id]
                                     (let [label (get labels label-id)
                                           answers (:labels (:answer label))]
                                       (mapcat (fn [[ith ithanswers]]
                                                 (mapv (fn [[uid answer]]
                                                         {:uid uid
                                                          :ith ith
                                                          :answer answer
                                                          :lid label-id
                                                          :aid article-id})
                                                       ithanswers))
                                               answers)))
                                   group-label-ids)
        group-events (for [{:keys [uid ith answer lid aid]} group-munge-labels]
                       [:review/set-label-value aid lid uid ith
                        (cond-> answer
                          (and (vector? answer) (= 1 (count answer)))
                          first)])
        ng-munge-labels (for [label-id non-group-label-ids]
                          {:label-id label-id :answer (:answer (get labels label-id))})
        ng-events (for [{:keys [label-id answer]} ng-munge-labels]
                    [:review/set-label-value article-id "na" label-id "na" answer])]
    (doseq [event (concat nil-events ng-events group-events)]
      (dispatch event))
    label-ids))

(defn ArticleLabelsView [article-id & {:keys [self-only? resolving?]}]
  (let [project-id @(subscribe [:active-project-id])
        self-id @(subscribe [:self/user-id])
        user-labels @(subscribe [:article/labels article-id])
        resolve-id @(subscribe [:article/resolve-user-id article-id])
        ann-context {:project-id project-id :article-id article-id :class "abstract"}
        ann-data-item (ann/annotator-data-item ann-context)
        ann-status-item [:annotator/status project-id]
        annotations @(subscribe ann-data-item)
        user-annotations (fn [user-id] (seq (->> (vals annotations)
                                                 (filter #(= (:user-id %) user-id)))))
        user-ids (sort (concat (keys user-labels)
                               (distinct (->> (vals annotations) (map :user-id) (remove nil?)))))
        user-confirmed? (fn [user-id]
                          (let [ulmap (get user-labels user-id)]
                            (every? #(true? (get-in ulmap [% :confirmed]))
                                    (keys ulmap))))
        some-real-answer? (fn [user-id]
                            (let [ulmap (get user-labels user-id)]
                              (some #(real-answer? (get-in ulmap [% :answer]))
                                    (keys ulmap))))
        resolved? (fn [user-id] (= user-id resolve-id))
        user-ids-resolved  (->> user-ids
                                (filter resolved?)
                                (filter some-real-answer?)
                                (filter user-confirmed?))
        user-ids-other     (->> user-ids
                                (remove resolved?)
                                (filter some-real-answer?)
                                (filter user-confirmed?))
        user-ids-annotated (->> user-ids
                                (filter user-annotations))
        user-ids-ordered   (distinct (cond->> (concat user-ids-resolved
                                                      user-ids-other
                                                      user-ids-annotated)
                                       self-only? (filter (partial = self-id))))]
    (dispatch [:require ann-data-item])
    (dispatch [:require ann-status-item])
    (when (seq user-ids-ordered)
      (with-loader [[:article project-id article-id]]
        {:class "ui segments article-labels-view"}
        (doall
         (for [user-id user-ids-ordered]
           (let [user-name @(subscribe [:user/display user-id])
                 all-times (->> (vals (get user-labels user-id))
                                (map :confirm-epoch)
                                (remove nil?)
                                (remove zero?))
                 updated-time (if (empty? all-times) (t/now)
                                  (time-from-epoch (apply max all-times)))]
             (doall
              (concat
               [[:div.ui.segment {:key [:user user-id]}
                 [:h5.ui.dividing.header
                  [:div.ui.two.column.middle.aligned.grid>div.row
                   [:div.column
                    (if self-only? "Your Labels"
                        [:div
                         [Avatar {:user-id user-id}]
                         [UserPublicProfileLink {:user-id user-id
                                                 :display-name user-name}]
                         (when resolving?
                           (r/as-element
                            [Button {:id "copy-label-button" :class "project-access"
                                     :size "tiny" :style {:margin-left "0.25rem"}
                                     :on-click #(copy-user-answers project-id article-id user-id)}
                             [Icon {:name "copy"}] "copy"]))])]
                   [:div.right.aligned.column
                    [updated-time-label updated-time]]]]
                 [:div.labels
                  [ArticleLabelValuesView article-id user-id]]
                 (let [note-content @(subscribe [:article/notes article-id user-id "default"])]
                   (when (and (string? note-content) (not-empty (str/trim note-content)))
                     [:div.notes [note-content-label "default" note-content]]))]])))))))))
