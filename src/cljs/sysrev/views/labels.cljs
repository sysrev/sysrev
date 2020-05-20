(ns sysrev.views.labels
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe dispatch]]
            [cljs-time.core :as t]
            [sysrev.views.components.core :refer [updated-time-label note-content-label]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.annotator :as ann]
            [sysrev.views.semantic :refer [Table TableHeader TableHeaderCell TableRow TableBody TableCell]]
            [sysrev.state.label :refer [real-answer?]]
            [sysrev.util :as util :refer [in? css time-from-epoch nbsp]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn ValueDisplay [root-label-id label-id answer]
  (let [inclusion @(subscribe [:label/answer-inclusion root-label-id label-id answer])
        color (case inclusion
                true   "green"
                false  "orange"
                nil)
        values (if (= "boolean" @(subscribe [:label/value-type root-label-id label-id]))
                 (if (boolean? answer)
                   [answer] [])
                 (cond (nil? answer)        nil
                       (sequential? answer) answer
                       :else                [answer]))]
    [:span {:class (when color (str color "-text"))}
     (if (empty? values)
       [:i.grey.question.circle.icon
        {:style {:margin-right "0"}
         :aria-hidden true}]
       (str/join ", " values))]))

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

(defn GroupLabelAnswerTag [group-label-id answers]
  (let [labels (->> (vals @(subscribe [:label/labels "na" group-label-id "na"]))
                    (sort-by :project-ordering <)
                    (filter :enabled))
        display @(subscribe [:label/display "na" group-label-id])
        display-label (if (= "boolean" @(subscribe [:label/value-type "na" group-label-id]))
                        (str display "?")
                        display)]
    (when (seq answers)
      [:div.ui.tiny.labeled.button.label-answer-tag
       [Table {:striped true}
        [TableHeader {:fullWidth true}
         [TableRow {:textAlign "center"}
          [TableHeaderCell {:colSpan (count labels)} display-label]]]
        [TableHeader
         [TableRow
          (doall (for [label labels]
                   ^{:key (str group-label-id "-" (:label-id label) "-table-header" )}
                   [TableHeaderCell @(subscribe [:label/display group-label-id (:label-id label)])]))]]
        [TableBody
         (for [ith (sort (keys answers))]
           ^{:key (str group-label-id "-" ith "-row")}
           [TableRow
            (for [label labels]
              ^{:key (str group-label-id "-" ith "-row-" (:label-id label) "-cell")}
              [TableCell
               [ValueDisplay group-label-id (:label-id label) (get-in answers [ith (:label-id label)])]])])]]])))

(defn LabelValuesView [labels & {:keys [notes user-name resolved?]}]
  (let [dark-theme? @(subscribe [:self/dark-theme?])]
    [:div.label-values
     (when user-name
       [:div.ui.label.user-name {:class (css [(not dark-theme?) "basic"])}
        user-name])
     ;; basic labels
     (doall (for [[label-id answer] (->> @(subscribe [:project/label-ids])
                                         (filter #(contains? labels %))
                                         (remove #(= "group" @(subscribe [:label/value-type "na" %])))
                                         (map #(list % (get-in labels [% :answer]))))]
              (when (real-answer? answer)
                ^{:key (str label-id)} [LabelAnswerTag "na" label-id answer])))
     ;; group labels
     (doall (for [[group-label-id answer] (->> @(subscribe [:project/label-ids])
                                               (filter #(contains? labels %))
                                               (remove #(not= "group" @(subscribe [:label/value-type "na" %])))
                                               (map #(list % (get-in labels [% :answer]))))]
              ^{:key (str group-label-id)}
              [GroupLabelAnswerTag group-label-id (:labels answer)]))
     (when (and (some #(contains? % :confirm-time) (vals labels))
                (some #(in? [0 nil] (:confirm-time %)) (vals labels)))
       [:div.ui.basic.yellow.label.labels-status "Unconfirmed"])
     (when resolved?
       [:div.ui.basic.purple.label.labels-status "Resolved"])
     (doall (for [note-name (keys notes)] ^{:key [note-name]}
              [note-content-label note-name (get notes note-name)]))] ))

(defn- ArticleLabelValuesView [article-id user-id]
  (let [labels @(subscribe [:article/labels article-id user-id])
        resolved? (= user-id @(subscribe [:article/resolve-user-id article-id]))]
    [LabelValuesView labels :resolved? resolved?]))

(defn ArticleLabelsView [article-id & {:keys [self-only?]}]
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
        user-ids-resolved (->> user-ids
                               (filter resolved?)
                               (filter some-real-answer?)
                               (filter user-confirmed?))
        user-ids-other (->> user-ids
                            (remove resolved?)
                            (filter some-real-answer?)
                            (filter user-confirmed?))
        user-ids-annotated (->> user-ids (filter user-annotations))
        user-ids-ordered (cond->> (concat user-ids-resolved
                                          user-ids-other
                                          user-ids-annotated)
                           self-only? (filter (partial = self-id))
                           true distinct)]
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
                                                 :display-name user-name}]])]
                   [:div.right.aligned.column
                    [updated-time-label updated-time]]]]
                 [:div.labels
                  [ArticleLabelValuesView article-id user-id]]
                 (let [note-content @(subscribe [:article/notes article-id user-id "default"])]
                   (when (and (string? note-content) (not-empty (str/trim note-content)))
                     [:div.notes [note-content-label "default" note-content]]))]]
               (when (and @(subscribe [:have? ann-data-item])
                          @(subscribe [:have? ann-status-item]))
                 (when-let [entries (user-annotations user-id)]
                   (doall
                    (for [{:keys [annotation-id selection semantic-class annotation]} entries]
                      [:div.ui.form.segment.user-annotation {:key [:annotation annotation-id]}
                       [:div.field>div.three.fields
                        [:div.field [:label "Selection"]
                         [:div.ui.fluid.basic.label
                          (or (some-> selection str pr-str) nbsp)]]
                        [:div.field [:label "Semantic Class"]
                         [:div.ui.fluid.basic.label
                          (or (some-> semantic-class str not-empty) nbsp)]]
                        [:div.field [:label "Value"]
                         [:div.ui.fluid.basic.label
                          (or (some-> annotation str not-empty) nbsp)]]]])))))))))))))
