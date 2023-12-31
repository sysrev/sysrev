(ns sysrev.views.review
  (:require [clojure.string :as str]
            [medley.core :as medley]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-sub reg-event-fx trim-v]]
            [sysrev.action.core :as action]
            [sysrev.data.core :as data]
            [sysrev.loading :as loading]
            [sysrev.state.nav :as nav :refer [project-uri]]
            [sysrev.state.label :as label :refer [get-label-raw]]
            [sysrev.state.note :refer [sync-article-notes]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.semantic :as S :refer [Icon Message]]
            [sysrev.util :as util :refer [in? css nbsp]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn set-label-value [db article-id root-label-id label-id ith label-value]
  (if (= root-label-id "na") ; not a group label
    (assoc-in db [:state :review :labels article-id label-id]
              label-value)
    (assoc-in db [:state :review :labels article-id root-label-id :labels ith label-id]
              label-value)))

(defn check-validatable-label [root-label-id label-id ith label-value]
  (let [project-id @(subscribe [:active-project-id])
        definition @(subscribe [::label/definition root-label-id label-id project-id])]
    (when (:validatable-label? definition)
      (try
        (->
          (js/fetch (str "https://resolver.api.identifiers.org/" (js/encodeURIComponent label-value)))
          (.then (fn [res]
                   (-> (.json ^js res)
                       (.then (fn [data-aux]
                                (let [data (js->clj data-aux :keywordize-keys true)
                                      valid-id? (seq (get-in data [:payload :resolvedResources]))]
                                  (dispatch [::validate-value-id root-label-id label-id ith valid-id?]))))))))
        (catch js/Error _e
          (dispatch [::validate-value-id root-label-id label-id ith false]))))))

(reg-event-fx :review/set-label-value
              (fn [{:keys [db]} [kw article-id root-label-id label-id ith label-value]]
                (check-validatable-label root-label-id label-id ith label-value)
                {:db (set-label-value db article-id root-label-id label-id ith label-value)
                 :fx [[:dispatch
                       [:review/record-reviewer-event
                        [kw {:article-id article-id
                             :project-id (nav/active-project-id db)}]]]]}))

;; Adds a value to an active label answer vector
(reg-event-fx ::add-label-value
              (fn [{:keys [db]} [kw article-id root-label-id label-id ith label-value]]
                (let [current-values
                      (if (= root-label-id "na")
                        (get @(subscribe [:review/active-labels article-id]) label-id)
                        (get-in @(subscribe [:review/active-labels article-id])
                                [root-label-id :labels ith label-id]))]
                  {:db (set-label-value db article-id root-label-id label-id ith
                                        (-> (concat current-values [label-value])
                                            distinct vec))
                   :fx [[:dispatch
                         [:review/record-reviewer-event
                          [kw {:article-id article-id
                               :project-id (nav/active-project-id db)}]]]]})))

(reg-event-fx ::remove-string-value
              (fn [{:keys [db]} [kw article-id root-label-id label-id ith value-idx curvals]]
                {:db (set-label-value db article-id root-label-id label-id ith
                                      (->> (assoc (vec curvals) value-idx "")
                                           (filterv not-empty)))
                 :fx [[:dispatch
                       [:review/record-reviewer-event
                        [kw {:article-id article-id
                             :project-id (nav/active-project-id db)}]]]]}))

(reg-event-fx ::validate-value-id
              (fn [{:keys [db]} [_ root-label-id label-id ith valid-id?]]
                {:db (assoc-in db [:state :identifier-validations root-label-id label-id ith] (boolean valid-id?))}))

(reg-event-fx ::set-string-value
              (fn [{:keys [db]} [kw article-id root-label-id label-id ith value-idx label-value curvals]]
                (check-validatable-label root-label-id label-id nil label-value)
                {:db (set-label-value db article-id root-label-id label-id ith
                                      (assoc (vec curvals) value-idx label-value))
                 :fx [[:dispatch
                       [:review/record-reviewer-event
                        [kw {:article-id article-id
                             :project-id (nav/active-project-id db)}]]]]}))

(reg-event-fx ::extend-string-answer
              (fn [{:keys [db]} [kw article-id root-label-id label-id ith curvals]]
                {:db (set-label-value db article-id root-label-id label-id ith
                                      (assoc (vec curvals) (count curvals) ""))
                 :fx [[:dispatch
                       [:review/record-reviewer-event
                        [kw {:article-id article-id
                             :project-id (nav/active-project-id db)}]]]]}))

;; Simulates an "enable value" label input component event
(reg-event-fx :review/trigger-enable-label-value
              (fn [{:keys [db]} [kw article-id root-label-id label-id ith label-value]]
                (let [{:keys [value-type]} (get-label-raw db label-id)
                      record-fx [:dispatch
                                 [:review/record-reviewer-event
                                  [kw {:article-id article-id
                                       :project-id (nav/active-project-id db)}]]]]
                  (condp = value-type
                    "boolean"
                    {:db (set-label-value db article-id root-label-id
                                          label-id ith label-value)
                     :fx [record-fx]}
                    "categorical"
                    {:fx [[::add-label-value [article-id root-label-id
                                              label-id ith label-value]]
                          record-fx]}))))

(defn is-annotatable?
  "Checks that an article is annotatable to properly ignore relationship labels on unsupported articles
   Looks at the dom for an instance of the brat annotator - alternative is to pass in article ID Which is messy"
  []
  (let [elem (.getElementById js/document "Brat_Renderer")]
    (if elem
      true
      false)))

(defn missing-answer? [{:keys [enabled required value-type]} answer]
  (and enabled required
       (case value-type
         "boolean" (not (boolean? answer))
         "categorical" (empty? answer)
         "string" (str/blank? answer)
         "annotation" (empty? answer)
         "group" (empty? answer)
         "relationship" (and (not answer) (is-annotatable?)))))

(defn missing-group-answer? [labels answers]
  (boolean
   (->> labels
        (some (fn [[label-id label]]
                (missing-answer? label (get answers label-id)))))))

(reg-sub :review/missing-labels
         (fn [[_ project-id article-id]]
           [(subscribe [:project/labels-raw project-id])
            (subscribe [:review/active-labels article-id])])
         (fn [[labels-raw labels]]
           (->> labels-raw
             (medley/filter-kv
               (fn [label-id label]
                 (if (:labels label)
                   (some #(missing-group-answer? (:labels label) %)
                         (-> labels (get label-id) :labels vals))
                   (missing-answer? label (get labels label-id))))))))

(defn valid-string-value? [{:keys [max-length multi? regex] :as definition} answer]
  (if (sequential? answer)
    (if (or multi? (<= (count answer) 1))
        (every? #(valid-string-value? definition %) answer)
        false)
    (boolean
     (and (string? answer)
          (<= (count answer) max-length)
          (or (empty? regex)
              (empty? answer)
              (some #(re-matches (re-pattern %) answer) regex))))))

(defn valid-answer? [value-type answer & [{:keys [all-values] :as definition}]]
  (case value-type
    "boolean"
    (or (boolean? answer) (nil? answer))
    "categorical"
    (or (empty? answer)
      (boolean
        (every?
          #(some (partial = %) all-values)
          answer)))
    "annotation"
    (or (empty? answer)
      (boolean
        (every?
          #(some? (:value %))
          (vals answer))))
    "string"
    (or (empty? answer)
        (valid-string-value? definition answer))
    "group" (empty? answer)
    "relationship" true))

(defn valid-answer-id? [definition root-label-id label-id identifier-validations]
  (or (not (-> definition :validatable-label?))
      (->> (vals (get-in identifier-validations [root-label-id label-id]))
           (filter false?)
           (empty?))))

(defn valid-group-answers? [label answer identifier-validations]
  (let [root-label-id (:label-id label)
        group-labels (:labels label)
        group-answers (vals (:labels answer))]
    (every?
      (fn [[label-id answer]]
        (let [label (get group-labels label-id)]
          (and
            (valid-answer-id? (:definition label) root-label-id label-id identifier-validations)
            (valid-answer? (:value-type label) answer (:definition label)))))
      (apply concat group-answers))))

(reg-sub :review/invalid-labels
         (fn [[_ project-id article-id]]
           [(subscribe [:project/labels-raw project-id])
            (subscribe [:review/active-labels article-id])
            (subscribe [:review/identifier-validations])])
         (fn [[labels-raw labels identifier-validations]]
           (->> labels
                (medley/map-kv-vals
                 (fn [label-id answer]
                   (let [label (get labels-raw label-id)]
                     (when-not (if (:labels answer)
                                 (valid-group-answers? label answer identifier-validations)
                                 (and
                                  (valid-answer-id? (:definition label) "na" (:label-id label) identifier-validations)
                                  (valid-answer? (:value-type label) answer
                                                 (:definition label))))
                       label))))
                (medley/remove-vals nil?))))

(reg-sub :review/identifier-validations
         (fn [db [_]]
           (get-in db [:state :identifier-validations])))

(reg-sub :review/valid-id?
         (fn [db [_ root-label-id label-id ith]]
           (let [identifier-validation (get-in db [:state :identifier-validations root-label-id label-id ith])]
             (or (nil? identifier-validation) identifier-validation))))

(defn BooleanLabelInput [[root-label-id label-id ith] article-id]
  (let [answer (subscribe [:review/active-labels
                           article-id root-label-id label-id ith])]
    [ui/ThreeStateSelection
     {:set-answer! #(dispatch [:review/set-label-value
                               article-id root-label-id label-id ith %])
      :value answer}]))

(defn CategoricalLabelInput [[root-label-id label-id ith] article-id]
  (let [dom-class (str "label-edit-" article-id "-" root-label-id "-" label-id "-" ith)]
    (when (= article-id @(subscribe [:review/editing-id]))
      (let [required?      @(subscribe [:label/required? root-label-id label-id])
            all-values     @(subscribe [:label/all-values root-label-id label-id])
            current-values @(subscribe [:review/active-labels
                                        article-id root-label-id label-id ith])
            touchscreen?   @(subscribe [:touchscreen?])]
        [S/Dropdown {:key [:dropdown dom-class]
                     :class dom-class
                     :size "small" :fluid true
                     :selection true :multiple true :icon "dropdown"
                     :search (not touchscreen?)
                     :options (vec (for [[i v] (map-indexed vector all-values)]
                                     {:key i
                                      :value v
                                      :text v}))
                     :value (into [] current-values)
                     :close-on-change true
                     :placeholder (when (empty? current-values)
                                    (str "No answer selected"
                                         (when required? " (required)")))
                     :on-change (fn [_e x]
                                  (dispatch [:review/set-label-value
                                             article-id root-label-id label-id ith
                                             (js->clj (.-value x))]))}]))))

(defn AnnotationLabelInput [[root-label-id label-id ith] article-id]
  (let [dom-class (str "label-edit-" article-id "-" root-label-id "-" label-id "-" ith)
        article-id @(subscribe [:review/article-id])
        ann-context {:project-id @(subscribe [:active-project-id])
                     :article-id article-id
                     :class "abstract"}]
    (when (= article-id @(subscribe [:review/editing-id]))
      (let [required?      @(subscribe [:label/required? root-label-id label-id])
            current-values @(subscribe [:review/active-labels
                                        article-id root-label-id label-id ith])
            current-values-count (count current-values)]
        [:div {:class dom-class}
         [:div.ui.grid
          [:div.center.aligned.column
           {:on-click #(dispatch [:set-review-annotation-interface
                                  ann-context
                                  {:root-label-id root-label-id
                                   :label-id label-id
                                   :article-id article-id
                                   :ith ith}
                                  current-values])}
           [:button.ui.primary.button
            "Annotate"]]]
         (if (empty? current-values)
           [:div {:style {:text-align "center"
                          :margin "0.5rem 0"}
                  :class  "missing-label-answer"
                  :div (str "missing-label-answer " label-id)}
            "No answers selected"
            (when required?
              " (required)")]

           [:div {:style {:text-align "center"
                          :margin "0.5rem 0"}}
            [:span.ui.text.success
             (if (= current-values-count 1)
               "1 annotation set"
               (str current-values-count " annotations set"))]])]))))

(defn StringLabelInput
  [[root-label-id label-id ith] article-id]
  (let [multi? @(subscribe [:label/multi? root-label-id label-id])
        class-for-idx #(str root-label-id "_" label-id "-" ith "__value_" %)
        curvals (or (not-empty @(subscribe [:review/active-labels
                                            article-id root-label-id label-id ith]))
                    [""])
        nvals (count curvals)]
    (when (= article-id @(subscribe [:review/editing-id]))
      [:div.inner
       (doall
        (->>
         (or (not-empty @(subscribe [:review/active-labels
                                     article-id root-label-id label-id ith]))
             [""])
         (map-indexed
          (fn [i val]
            (let [left-action? true
                  right-action? (and multi? (= i (dec nvals)))
                  validatable-label? @(subscribe [:label/validatable-label? root-label-id label-id])
                  valid-id? (or (not validatable-label?)
                                @(subscribe [:review/valid-id? root-label-id label-id]))
                  valid? (and valid-id? @(subscribe [:label/valid-string-value?
                                                      root-label-id label-id val]))
                  focus-elt (fn [value-idx]
                              #(js/setTimeout
                                (fn []
                                  (some->
                                   (js/document.querySelector (str "." (class-for-idx value-idx) ":visible"))
                                   .focus))
                                25))
                  focus-prev (focus-elt (dec i))
                  focus-next (focus-elt (inc i))
                  can-add? (and right-action? (not-empty val))
                  add-next (when can-add?
                             #(do (dispatch-sync [::extend-string-answer
                                                  article-id root-label-id label-id
                                                  ith curvals])
                                  (focus-next)))
                  add-next-handler (util/wrap-user-event add-next
                                                         :prevent-default true
                                                         :stop-propagation true
                                                         :timeout false)
                  can-delete? (not (and (= i 0) (= nvals 1) (empty? val)))
                  delete-current #(do (dispatch-sync
                                       [::remove-string-value
                                        article-id root-label-id label-id
                                        ith i curvals])
                                      (focus-prev))]
              ^{:key [root-label-id label-id ith i]}
              [:div.ui.small.form.string-label
               [:div.field.string-label {:class (css [(empty? val) ""
                                                      (and valid? valid-id?) "success"
                                                      :else        "error"])}
                [:div.ui.fluid.input
                 {:class (css [(and left-action? right-action?) "labeled right action"
                               left-action? "left action"])}
                 (when left-action?
                   [:div.ui.label.icon.button.input-remove
                    {:class (css [(not can-delete?) "disabled"])
                     :on-click (util/wrap-user-event delete-current
                                                     :prevent-default true
                                                     :timeout false)}
                    [:i.times.icon]])
                 [:input {:type "text"
                          :class (class-for-idx i)
                          :value val
                          :on-change
                          (util/on-event-value
                            (fn [value]
                              (dispatch-sync [::set-string-value
                                              article-id root-label-id label-id
                                              ith i value curvals])))
                          :on-key-down
                          #(cond (= "Enter" (.-key %))
                                 (if add-next (add-next) (focus-next))
                                 (and (in? ["Backspace" "Delete" "Del"] (.-key %))
                                      (empty? val) can-delete?)
                                 (delete-current)
                                 :else true)}]
                 (when right-action?
                   [:div.ui.icon.button.input-row
                    {:class (css [(not can-add?) "disabled"])
                     :on-click add-next-handler}
                    [:i.plus.icon]])]]
               (when (and (not valid?)
                          (not (str/blank? val)))
                 [Message {:color "red"} "Invalid Value"])
               (when (and validatable-label? valid-id?)
                 [:div
                  [:a {:target "_blank"
                       :style {:margin-top "5px"}
                       :href (str "https://identifiers.org/" val)}
                   [:i.external.alternate.icon]
                   " Identifier info"]])])))))])))

(defn RelationshipLabelInput []
  [:div [:p "This project has an annotator label configured. If applicable to the document type,
             you'll see an annotator in the right hand pane."]])


(defn- inclusion-tag [article-id root-label-id label-id ith]
  (let [criteria? @(subscribe [:label/inclusion-criteria? root-label-id label-id])
        answer @(subscribe [:review/active-labels article-id root-label-id label-id ith])
        inclusion @(subscribe [:label/answer-inclusion root-label-id label-id answer])]
    (if criteria?
      [:i.left.floated.fitted {:class (css [(true? inclusion)   "green circle plus"
                                            (false? inclusion)  "orange circle minus"
                                            (nil? inclusion)    "grey circle outline"]
                                           "icon")}]
      [Icon {:style {:margin "0"}}])))

(defn LabelHelpPopup [{:keys [category required question definition]}]
  (let [criteria? (= category "inclusion criteria")
        required? required
        examples (:examples definition)]
    [:div.ui.grid.label-help
     [:div.middle.aligned.center.aligned.row.label-help-header
      [:div.ui.sixteen.wide.column
       [:span (str (cond (and criteria? required?)
                         "Required - Inclusion Criteria"
                         (and criteria? (not required?))
                         "Optional - Inclusion Criteria"
                         required?
                         "Required Label"
                         :else
                         "Optional Label"))]]]
     [:div.middle.aligned.row.label-help-question
      [:div.sixteen.wide.column.label-help
       [:div [:span (str question)]]
       (when (seq examples)
         [:div
          [:div.ui.small.divider]
          [:div
           [:strong "Examples: "]
           (doall (map-indexed (fn [i ex] ^{:key i}
                                 [:div.ui.small.green.label (str ex)])
                               examples))]])]]]))

(defn GroupLabelInput [label-id article-id]
  (let [label-name @(subscribe [:label/display "na" label-id])
        active-group-label (subscribe [:group-label/active-group-label])
        answers (subscribe [:review/active-labels article-id "na" label-id])]
    [:div {:id (str "group-label-input-" label-id)
           :class "group-label-input"
           :style (cond-> {:width "100%"
                           :padding-top "1em"
                           :padding-bottom "1em"
                           :padding-left "1em"}
                    (= label-id @active-group-label)
                    (merge {:border-radius ".28571429rem"
                            :border "1px solid"
                            :background "#e0e1e2"
                            :color "black"}))
           :on-click (fn [_]
                       (if (not= label-id @active-group-label)
                         (dispatch [:group-label/set-active-group-label label-id])
                         (dispatch [:group-label/set-active-group-label nil])))}
     label-name [:div {:style {:float "right"
                               :padding-right "0.5em"}}
                 (str (count (:labels @answers)) " "
                      (util/pluralize (count (:labels @answers)) "Row"))]]))

(reg-sub ::label-css-class
         (fn [[_ article-id label-id]]
           [(subscribe [:review/inconsistent-labels article-id label-id])
            (subscribe [:label/required? "na" label-id])
            (subscribe [:label/inclusion-criteria? "na" label-id])])
         (fn [[inconsistent? required? criteria?]]
           (cond inconsistent?   "inconsistent"
                 required?       "required"
                 (not criteria?) "extra"
                 :else           "")))

(defn GroupLabelColumn [article-id label-id]
  [GroupLabelInput label-id article-id])

;; Component for label column in inputs grid
(defn- LabelColumn [article-id label-id row-position n-cols label-position]
  (let [label @(subscribe [:sysrev.state.label/label "na" label-id])
        value-type @(subscribe [:label/value-type "na" label-id])
        label-css-class @(subscribe [::label-css-class article-id label-id])
        label-string @(subscribe [:label/display "na" label-id])
        question @(subscribe [:label/question "na" label-id])
        on-click-help (util/wrap-user-event #(do nil) :timeout false)
        answer @(subscribe [:review/active-labels article-id "na" label-id "na"])]
    (when (not (-> label :definition :hidden-label?))
      [:div.ui.column.label-edit {:class label-css-class
                                  :data-label-id (str label-id)
                                  :data-short-label (str label-string)}
       [:div.ui.middle.aligned.grid.label-edit
        [ui/Tooltip
         {:class "label-help"
          :basic true
          :hoverable false
          :distance-away 6
          :position (if (= n-cols 1)
                      (if (<= label-position 1) "bottom center" "top center")
                      (cond (= row-position :left)   "top left"
                            (= row-position :right)  "top right"
                            :else                    "top center"))
          :trigger (let [name-content [:span.name {:class (css [(>= (count label-string) 30)
                                                                "small-text"])}
                                       [:span.inner.short-label label-string]]]
                     (if (and (util/mobile?) (>= (count label-string) 30))
                       [:div.ui.row.label-edit-name {:on-click on-click-help}
                        [inclusion-tag article-id "na" label-id "na"]
                        [:span.name " "]
                        (when (seq question)
                          [:i.right.floated.fitted.grey.circle.question.mark.icon])
                        [:div.clear name-content]]
                       [:div.ui.row.label-edit-name {:on-click on-click-help
                                                     :style {:cursor "help"}}
                        [inclusion-tag article-id "na" label-id "na"]
                        name-content
                        (when (seq question)
                          [:i.right.floated.fitted.grey.circle.question.mark.icon])]))
          :tooltip [LabelHelpPopup
                    {:category @(subscribe [:label/category "na" label-id])
                     :required @(subscribe [:label/required? "na" label-id])
                     :question @(subscribe [:label/question "na" label-id])
                     :definition {:examples @(subscribe [:label/examples
                                                         "na" label-id])}}]}]
        [:div.ui.row.label-edit-value {:class (condp = value-type
                                                "boolean"      "boolean"
                                                "categorical"  "category"
                                                "annotation"   "annotation"
                                                "string"       "string"
                                                "")}
         [:div.inner (condp = value-type
                       "boolean" [BooleanLabelInput ["na" label-id "na"] article-id]
                       "categorical" [CategoricalLabelInput ["na" label-id "na"] article-id]
                       "annotation" [AnnotationLabelInput ["na" label-id "na"] article-id]
                       "string" [StringLabelInput ["na" label-id "na"] article-id]
                       "relationship" [RelationshipLabelInput]
                       [:div "unknown label - label-column"])]]]
       (when (missing-answer? label answer)
         [:div {:style {:text-align "center"
                        :margin-bottom "0.5rem"}
                :class  "missing-label-answer"
                :div (str "missing-label-answer " label-id)} "Required"])])))

(defn- NoteInputElement []
  (when @(subscribe [:project/raw])
    (let [article-id @(subscribe [:review/editing-id])
          note-content @(subscribe [:review/active-note article-id])]
      [:div.ui.segment.notes>div.ui.middle.aligned.form.notes>div.middle.aligned.field.notes
       [:label.middle.aligned.notes "Notes" [:i.large.middle.aligned.grey.write.icon]]
       [:textarea {:type "text"
                   :rows 2
                   :value (or note-content "")
                   :on-change (util/on-event-value
                               #(dispatch-sync [:review/set-note-content article-id %]))}]])))

(defn- ActivityReport []
  (when-let [today-count @(subscribe [:review/today-count])]
    (if (util/full-size?)
      [:div.ui.label.activity-report
       [:span.ui.green.circular.label today-count]
       [:span.text nbsp nbsp "finished today"]]
      [:div.ui.label.activity-report
       [:span.ui.tiny.green.circular.label today-count]
       [:span.text nbsp nbsp "today"]])))

(reg-sub :review/saving?
         (fn [[_ article-id]] (subscribe [:panel-field [:saving-labels article-id]]))
         (fn [saving?] saving?))

;; Record POST action to send labels having been initiated,
;; to show loading indicator on the button that was clicked.
(reg-event-fx :review/mark-saving [trim-v]
              (fn [_ [article-id panel]]
                {:dispatch [:set-panel-field [:saving-labels article-id] true panel]}))

;; Reset state set by :review/mark-saving
(reg-event-fx :review/reset-saving [trim-v]
              (fn [_ [article-id panel]]
                (let [field (if article-id [:saving-labels article-id] [:saving-labels])]
                  {:dispatch [:set-panel-field field nil panel]})))

(defn- review-task-saving? [article-id]
  (and @(subscribe [:review/saving? article-id])
       (or (action/running? :review/send-labels)
           (data/loading? #{:article :review/task}))))

(defn- review-task-ready-for-action? []
  (and (loading/ajax-status-inactive? 50)
       (zero? (-> (js/document.querySelectorAll "div.view-pdf.rendering") .-length))
       (zero? (-> (js/document.querySelectorAll "div.view-pdf.updating") .-length))))

(defn SaveButton [article-id & [small? fluid?]]
  (r/with-let [can-auto-save? (r/atom "NOT_SET")]
    (let [project-id @(subscribe [:active-project-id])
          resolving? @(subscribe [:review/resolving?])
          on-review-task? (subscribe [:review/on-review-task?])
          review-task-id @(subscribe [:review/task-id])
          missing @(subscribe [:review/missing-labels project-id article-id])
          invalid @(subscribe [:review/invalid-labels project-id article-id])
          saving? (review-task-saving? article-id)
          disabled? (or (seq missing) (seq invalid))
          settings @(subscribe [:project/settings])
          on-save (util/wrap-user-event
                   (fn []
                     (reset! can-auto-save? false) ; prevents infinite auto save loop if the server rejects the request
                     (util/run-after-condition
                      [:review-save article-id]
                      review-task-ready-for-action?
                      (fn []
                        (when-not (action/running? :review/send-labels)
                          (dispatch-sync [:review/mark-saving article-id])
                          (sync-article-notes article-id)
                          (dispatch
                           [:review/send-labels
                            {:project-id project-id
                             :article-id article-id
                             :confirm? true
                             :resolve? (boolean resolving?)
                             :on-success (do
                                          (reset! can-auto-save? "RESET")
                                          (concat
                                           (when (or @on-review-task? (= article-id review-task-id))
                                             [[:fetch [:review/task project-id]]])
                                           (when (not @on-review-task?)
                                             [[:fetch [:article project-id article-id]]
                                              [:review/disable-change-labels article-id]])))}]))))))
          button (fn [] [:button.ui.right.labeled.icon.button.save-labels
                         {:class (css [(or disabled? saving?) "disabled"]
                                      [saving? "loading"]
                                      [small? "tiny"]
                                      [fluid? "fluid"]
                                      [resolving? "purple" :else "primary"])
                          :on-click (when-not (or disabled? saving?) on-save)}
                         (when (= @can-auto-save? "NOT_SET"); this bit prevents an infinite loop when editing already saved labels
                           (reset! can-auto-save? (boolean disabled?)))
                         (when (= @can-auto-save? "RESET") ; this properly handles the render immediately after a save where disabled? isn't populated yet - othewise you can get false negitives
                           (reset! can-auto-save? "NOT_SET"))
                         (when (and (not disabled?) (not saving?) @can-auto-save? (:auto-save-labels settings))
                           ; automatically calls save when setting is true and the save button would be available
                           (on-save))
                         (str (if resolving? "Resolve" "Save") (when-not small? "Labels"))
                         [:i.check.circle.outline.icon]])]
      (if disabled?
        [ui/Tooltip
         {:trigger [:div [button]]
          :tooltip [:div {:style {:min-width "20em"}}
                    [:ul {:style {:margin 0
                                  :padding "0.15em"
                                  :padding-left "1.25em"}}
                     (when (seq missing)
                       [:li "Answer missing for required label(s): "
                        (->> missing vals (map :short-label) (str/join ", "))])
                     (when (seq invalid)
                       [:li "Invalid label answer(s): "
                        (->> invalid vals (map :short-label) (str/join ", "))])]]}]
        [button]))))

(defn SkipArticle [article-id & [small? fluid?]]
  (let [project-id @(subscribe [:active-project-id])
        saving? (review-task-saving? article-id)
        on-review-task? (subscribe [:review/on-review-task?])
        loading-task? (and (not saving?)
                           @on-review-task?
                           (data/loading? [:review/task project-id]))
        on-click (util/wrap-user-event
                  (fn []
                    (util/run-after-condition
                     [:review-skip article-id]
                     review-task-ready-for-action?
                     (fn []
                       (when @on-review-task?
                         (sync-article-notes article-id)
                         (dispatch [:fetch [:review/task project-id]])
                         (dispatch [:review/reset-ui-labels]))))))]
    (list ^{:key :skip-article}
          [:button.ui.right.labeled.icon.button.skip-article
           {:class (css [loading-task? "loading"]
                        [small? "tiny"]
                        [fluid? "fluid"])
            :on-click on-click}
           (if (and (util/full-size?) (not small?))
             "Skip Article" "Skip")
           [:i.right.circle.arrow.icon]])))

(defn make-label-columns [article-id label-ids n-cols]
  (doall (for [row (partition-all n-cols label-ids)]
           ^{:key [(first row)]}
           [:div.row
            (doall (for [i (range (count row))]
                     (let [label-id (nth row i)
                           row-position (cond (= i 0)            :left
                                              (= i (dec n-cols)) :right
                                              :else              :middle)
                           label-position (count (take-while #(not= % label-id)
                                                             label-ids))]
                       (if (= "group" @(subscribe [:label/value-type "na" label-id]))
                         ^{:key {:article-label [article-id label-id]}}
                         [GroupLabelColumn
                          article-id label-id row-position n-cols label-position]
                         ^{:key {:article-label [article-id label-id]}}
                         [LabelColumn
                          article-id label-id row-position n-cols label-position]))))
            (when (< (count row) n-cols) [:div.column])])))

(defn LabelsColumns [article-id & {:keys [n-cols class] :or {class "segment"}}]
  (let [n-cols (or n-cols (cond (util/full-size?) 4
                                (util/mobile?)    2
                                :else             3))
        label-ids @(subscribe [:project/label-ids])
        settings @(subscribe [:project/settings])]
    [:div.label-section
     {:class (css "ui" (util/num-to-english n-cols) "column celled grid" class)}
     (when (:auto-save-labels settings)
       [:p {:style {:fontSize "12px" :marginTop "1em"}} "This project has auto save enabled. Once all required labels are set it will save automatically. Set optional labels first to avoid missing data."])
     (when (some-> article-id (= @(subscribe [:review/editing-id])))
       (make-label-columns article-id label-ids n-cols))]))

(defn LabelDefinitionsButton []
  [:a.ui.tiny.icon.button {:href (project-uri nil "/labels/edit")
                           :on-click #(do (util/scroll-top) true)
                           :class (css [(util/full-size?) "labeled"])
                           :tabIndex "-1"}
   [:i.tags.icon] (when (util/full-size?) "Definitions")])

(defn ViewAllLabelsButton []
  (let [panel @(subscribe [:active-panel])]
    (when (not= panel [:project :project :articles])
      [:a.ui.tiny.primary.button
       {:class (css [(util/full-size?) "labeled icon"])
        :on-click
        (util/wrap-user-event
         #(dispatch [:project-articles/load-preset :self]))
        :tabIndex "-1"}
       (when (util/full-size?) [:i.user.icon])
       (if (util/full-size?) "View All Labels" "View Labels")])))

(defn display-sidebar? []
  (and (util/annotator-size?) @(subscribe [:review-interface])))

(defn- LabelEditorButtonsView
  "Component for row of action buttons below label inputs grid"
  [article-id]
  (let [on-review-task? @(subscribe [:review/on-review-task?])]
    [:div.ui.segment
     (if (util/full-size?)
       [:div.ui.center.aligned.middle.aligned.grid.label-editor-buttons-view
        [ui/CenteredColumn
         (when on-review-task? [ActivityReport])
         "left aligned four wide column"]
        [ui/CenteredColumn
         [:div.ui.grid.centered>div.ui.row
          (SaveButton article-id)
          (when on-review-task? (SkipArticle article-id))]
         "center aligned eight wide column"]
        [ui/CenteredColumn
         [:span]
         "right aligned four wide column"]]
       ;; mobile/tablet
       [:div.ui.center.aligned.middle.aligned.grid.label-editor-buttons-view
        [ui/CenteredColumn
         (when on-review-task? [ActivityReport])
         "left aligned four wide column"]
        [ui/CenteredColumn
         [:div.ui.center.aligned.grid>div.ui.row
          (SaveButton article-id true)
          (when on-review-task? (SkipArticle article-id true))]
         "center aligned eight wide column"]
        [ui/CenteredColumn
         [:span]
         "right aligned four wide column"]])]))

(defn LabelAnswerEditor
  "Top-level component for label editor"
  [article-id]
  (when article-id
    (when-let [project-id @(subscribe [:active-project-id])]
      (with-loader [[:article project-id article-id]] {}
        (if (not= article-id @(subscribe [:review/editing-id]))
          [:div]
          (let [change-set? @(subscribe [:review/change-labels? article-id])
                resolving? @(subscribe [:review/resolving?])]
            [:div.ui.segments.label-editor-view
             (when-not (display-sidebar?)
               [:div.ui.segment.label-editor-header
                [:div.ui.two.column.middle.aligned.grid
                 [:div.ui.left.aligned.column
                  [:h3 (if resolving? "Resolve Labels" "Set Labels")]]
                 [:div.ui.right.aligned.column
                  [LabelDefinitionsButton]
                  [ViewAllLabelsButton]
                  (when change-set?
                    [:div.ui.tiny.button
                     {:on-click (util/wrap-user-event
                                 #(dispatch [:review/disable-change-labels article-id]))}
                     "Cancel"])]]])
             (when-not (display-sidebar?)
               [LabelsColumns article-id])
             (when (display-sidebar?)
               [:div.ui.two.column.middle.aligned.grid.segment.no-sidebar
                [:div.column
                 [ActivityReport]]
                [:div.right.aligned.column
                 [LabelDefinitionsButton]
                 [ViewAllLabelsButton]]])
             [NoteInputElement]
             (when-not (display-sidebar?)
               [LabelEditorButtonsView article-id])]))))))

(defn LabelAnswerEditorColumn [_article-id]
  (r/create-class
   {:component-did-mount util/update-sidebar-height
    :reagent-render (fn [article-id]
                      [:div.label-editor-column>div.ui.segments.label-editor-view
                       [LabelsColumns article-id
                        :n-cols 1
                        :class "attached segment"]])}))

(defn SaveSkipColumnSegment [article-id]
  (let [review-task? @(subscribe [:review/on-review-task?])
        editing? @(subscribe [:review/editing?])]
    (when editing?
      [:div.label-editor-buttons-view
       {:class (css "ui" [review-task? "two" :else "one"] "column grid")
        :style {:margin-top "0em"}}
       [:div.column (SaveButton article-id true true)]
       (when review-task?
         [:div.column (SkipArticle article-id true true)])])))
