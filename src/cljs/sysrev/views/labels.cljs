(ns sysrev.views.labels
  (:require [clojure.string :as str]
            goog.object
            [medley.core :as medley]
            [re-frame.core :refer [subscribe dispatch]]
            [cljs-time.core :as t]
            [reagent.core :as r]
            [sysrev.views.components.core :as ui
             :refer [UpdatedTimeLabel NoteContentLabel]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.annotator :as ann]
            [sysrev.views.semantic :refer [Button Icon]]
            [sysrev.state.label :refer [real-answer?]]
            [sysrev.util :as util :refer [in? css time-from-epoch]]
            [sysrev.macros :refer-macros [with-loader]]
            ["@john-shaffer/data-grid" :as data-grid]))

;; I forked @material-ui/data-grid to remove ECMAScript 2018 code.
;; Don't try to use Editable grids! They probably won't work well.

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

(defn GroupLabelSearch [{:keys [on-change value]}]
  [:div {:class "group-label-search ui form fluid input"}
   [:input {:on-change on-change
            :value value}]])

(defn GroupLabelDataGrid [{:keys [group-label-name label-names rows search]}]
  (let [value-formatter #(let [v (.-value %)]
                           (if (sequential? v)
                             (str/join ", " v)
                             (str v)))
        csv-text (str/join "\n"
                           (concat
                             [(str/join "\t" label-names)]
                             (mapv #(str/join "\t" %) rows)))
        cols (->> label-names
                  (map-indexed #(-> #js{:field (pr-str %)
                                        :flex 1
                                        :headerName %2
                                        :valueGetter value-formatter}))
                  into-array)
        rows (->> rows
                  (map-indexed (fn [i row]
                                 (reduce-kv
                                   #(do (goog.object/set % (pr-str %2) %3)
                                        %)
                                   #js{:id (str i)}
                                   row)))
                  into-array)]
    [:div {:style {:display "flex"
                   :height "100%"
                   :width "100%"}}
     [:div {:style {:flex-grow "1"}}
      [:div {:class "group-label-title-container"}
       [:button.ui.button {:on-click (fn []
                                       (-> (aget js/navigator "clipboard") (.writeText csv-text))
                                       (dispatch [:alert {:content "Answers copied to clipboard" :opts {:success true}}]))}
        "Copy to clipboard"]
       [:div {:class "group-label-name"}
        group-label-name]
       [GroupLabelSearch search]]
      [:> data-grid/DataGrid
       {:auto-height true
        :columns cols
        :components {:Toolbar data-grid/GridToolbar}
        :disable-selection-on-click true
        :get-row-class-name (constantly "group-label-values-row")
        :rows rows
        :page-size 20
        :rows-per-page-options [10 20 50 100]}]]]))

(defn GroupLabelAnswerTable []
  (let [state (r/atom {:search-value ""})
        value-matches? (fn [search-value v]
                         (if (sequential? v)
                           (util/data-matches? (str/join ", " v) search-value)
                           (util/data-matches? (str v) search-value)))
        row-matches? (fn [search-value row]
                       (some #(value-matches? search-value %) row))]
    (fn [{:keys [group-label-id indexed? label-name labels rows]}]
      (let [{:keys [search-value]} @state
            label-names (mapv #(deref (subscribe [:label/display group-label-id (:label-id %)])) labels)
            rows (if (empty? search-value)
                   rows
                   (filterv (partial row-matches? search-value) rows))]
        [:div {:class "group-label-values"}
         [GroupLabelDataGrid
          {:group-label-name label-name
           :label-names label-names
           :rows rows
           :search
           {:on-change #(swap! state assoc :search-value
                               (.-value (.-target %)))
            :value search-value}}]]))))

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
       [NoteContentLabel note-name (get notes note-name)])]))

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
           (let [username @(subscribe [:user/username user-id])
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
                                                 :username username}]
                         (when resolving?
                           (r/as-element
                            [Button {:id "copy-label-button" :class "project-access"
                                     :size "tiny" :style {:margin-left "0.25rem"}
                                     :on-click #(copy-user-answers project-id article-id user-id)}
                             [Icon {:name "copy"}] "copy"]))])]
                   [:div.right.aligned.column
                    [UpdatedTimeLabel updated-time]]]]
                 [:div.labels
                  [ArticleLabelValuesView article-id user-id]]
                 (let [note-content @(subscribe [:article/notes article-id user-id "default"])]
                   (when (and (string? note-content) (not-empty (str/trim note-content)))
                     [:div.notes [NoteContentLabel "default" note-content]]))]])))))))))
