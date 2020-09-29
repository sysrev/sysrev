(ns sysrev.views.review
  (:require ["jquery" :as $]
            ["fomantic-ui"]
            [clojure.set :as set]
            [clojure.string :as str]
            [medley.core :as medley :refer [dissoc-in]]
            [reagent.core :as r]
            [reagent.dom :refer [dom-node]]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-sub
                                   reg-event-db reg-event-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :as nav :refer [project-uri]]
            [sysrev.state.label :refer [get-label-raw]]
            [sysrev.state.note :refer [sync-article-notes]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.semantic :as S :refer [Icon Message]]
            [sysrev.util :as util :refer [in? css nbsp]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn set-label-value [db article-id root-label-id label-id ith label-value]
  (cond
    ;; not a group label
    (= root-label-id "na")
    (assoc-in db [:state :review :labels article-id label-id]
              label-value)
    :else
    (assoc-in db [:state :review :labels article-id root-label-id :labels ith label-id] label-value)))

(reg-event-db :review/set-label-value [trim-v]
              (fn [db [article-id root-label-id label-id ith label-value]]
                (set-label-value db article-id root-label-id label-id ith label-value)))

;; Adds a value to an active label answer vector
(reg-event-db ::add-label-value [trim-v]
              (fn [db [article-id root-label-id label-id ith label-value]]
                (let [current-values (if (= root-label-id "na")
                                       (get @(subscribe [:review/active-labels article-id]) label-id)
                                       (get-in @(subscribe [:review/active-labels article-id]) [root-label-id :labels ith label-id]))]
                  (set-label-value db article-id root-label-id label-id ith
                                   (-> current-values (concat [label-value]) distinct vec)))))

;; Removes a value from an active label answer vector
(reg-event-db ::remove-label-value [trim-v]
              (fn [db [article-id root-label-id label-id ith label-value]]
                (let [current-values (if (= root-label-id "na")
                                       (get @(subscribe [:review/active-labels article-id]) label-id)
                                       (get-in @(subscribe [:review/active-labels article-id]) [root-label-id :labels ith label-id]))]
                  (set-label-value db article-id root-label-id label-id ith
                                   (->> current-values (remove (partial = label-value)) vec)))))

(reg-event-db ::remove-string-value [trim-v]
              (fn [db [article-id root-label-id label-id ith value-idx curvals]]
                (set-label-value db article-id root-label-id label-id ith
                                 (->> (assoc (vec curvals) value-idx "")
                                      (filterv not-empty)))))

(reg-event-db ::set-string-value [trim-v]
              (fn [db [article-id root-label-id label-id ith value-idx label-value curvals]]
                (set-label-value db article-id root-label-id label-id ith
                                 (assoc (vec curvals) value-idx label-value))))

(reg-event-db ::extend-string-answer [trim-v]
              (fn [db [article-id root-label-id label-id ith curvals]]
                (set-label-value db article-id root-label-id label-id ith
                                 (assoc (vec curvals) (count curvals) ""))))

;; Simulates an "enable value" label input component event
(reg-event-fx :review/trigger-enable-label-value [trim-v]
              (fn [{:keys [db]} [article-id root-label-id label-id ith label-value]]
                (let [{:keys [value-type]} (get-label-raw db label-id)]
                  (condp = value-type
                    "boolean"      {:db (set-label-value db article-id root-label-id label-id ith label-value)}
                    "categorical"  {::add-label-value [article-id root-label-id label-id ith label-value]}))))

;; missing labels
(reg-event-db :review/create-missing-label
              [trim-v]
              (fn [db [article-id root-label-id label-id ith]]
                (assoc-in db [:state :review :missing-labels article-id root-label-id label-id ith] true)))

(reg-event-db :review/delete-missing-label
              [trim-v]
              (fn [db [article-id root-label-id label-id ith]]
                (dissoc-in db [:state :review :missing-labels article-id root-label-id label-id ith])))

;; we don't care if it is a group label or not, we are just going to check
;; overall for missing labels
(reg-sub :review/missing-labels
         (fn [db [_ article-id]]
           (get-in db [:state :review :missing-labels article-id])))

;; invalid labels
(reg-event-db :review/create-invalid-label
              [trim-v]
              (fn [db [article-id root-label-id label-id ith]]
                (assoc-in db [:state :review :invalid-labels article-id root-label-id label-id ith] true)))

(reg-event-db :review/delete-invalid-label
              [trim-v]
              (fn [db [article-id root-label-id label-id ith]]
                (dissoc-in db [:state :review :invalid-labels article-id root-label-id label-id ith])))

(reg-sub :review/invalid-labels
         (fn [db [_ article-id]]
           (get-in db [:state :review :invalid-labels article-id])))

(defn BooleanLabelInput
  [[root-label-id label-id ith] article-id]
  (let [answer (subscribe [:review/active-labels article-id root-label-id label-id ith])]
    [ui/three-state-selection
     {:set-answer! #(dispatch [:review/set-label-value article-id root-label-id label-id ith %])
      :value answer}]))

(defn CategoricalLabelInput
  [[root-label-id label-id ith] article-id]
  (let [dom-class (str "label-edit-" article-id "-" root-label-id "-" label-id "-" ith)
        input-name (str "label-edit(" dom-class ")")]
    (r/create-class
     {:component-did-mount ; see https://github.com/mcku/UI-Dropdown/blob/master/dropdown.js for dropdown class options
      (fn [this]
        (->> {:duration 125 :action "hide"
              :onChange (fn [v _t] (dispatch [::add-label-value article-id root-label-id label-id ith v]))}
             (clj->js) (.dropdown ($ (dom-node this)) )))
      :reagent-render
      (fn [[root-label-id label-id ith] article-id]
        (when (= article-id @(subscribe [:review/editing-id]))
          (let [required?      @(subscribe [:label/required? root-label-id label-id ith])
                all-values     @(subscribe [:label/all-values root-label-id label-id ith])
                current-values @(subscribe [:review/active-labels article-id root-label-id label-id ith])
                touchscreen?   @(subscribe [:touchscreen?])
                unsel-values   (vec (set/difference (set all-values) (set current-values)))
                on-deselect    (fn [v] #(dispatch [::remove-label-value
                                                   article-id root-label-id label-id ith v]))]
            [(if touchscreen?
               :div.ui.small.fluid.multiple.selection.dropdown
               :div.ui.small.fluid.search.selection.dropdown.multiple)
             {:key [:dropdown dom-class] :class dom-class}
             (map (fn [v] [:a.ui.label {:key [(str "sel-" label-id "-" v)] :on-click (on-deselect v)}
                           v [:i.delete.icon]]) current-values)
             [:input {:name input-name :value (str/join "," current-values) :type "hidden"}]
             [:i.dropdown.icon]
             (when (empty? current-values)
               [:div.default.text "No answer selected"
                (when required? [:span.default.bold "(required)"])])
             [:div.menu (map (fn [v] ^{:key [v]} [:div.item {:data-value v} v]) unsel-values)]])))})))

(defn StringLabelInput
  [[root-label-id label-id ith] article-id]
  (let [multi? @(subscribe [:label/multi? root-label-id label-id])
        class-for-idx #(str root-label-id "_" label-id "-" ith "__value_" %)]
    (r/create-class
     {:reagent-render
      (fn [[root-label-id label-id ith] article-id]
        (let [curvals (or (not-empty @(subscribe [:review/active-labels article-id root-label-id label-id ith]))
                          [""])
              nvals (count curvals)]
          (when (= article-id @(subscribe [:review/editing-id]))
            [:div.inner
             (doall
              (->>
               (or (not-empty @(subscribe [:review/active-labels article-id root-label-id label-id ith]))
                   [""])
               (map-indexed
                (fn [i val]
                  (let [left-action? true
                        right-action? (and multi? (= i (dec nvals)))
                        valid? @(subscribe [:label/valid-string-value? root-label-id label-id val])
                        focus-elt (fn [value-idx]
                                    #(js/setTimeout
                                      (fn []
                                        (some->
                                         ($ (str "." (class-for-idx value-idx) ":visible"))
                                         (.focus)))
                                      25))
                        focus-prev (focus-elt (dec i))
                        focus-next (focus-elt (inc i))
                        can-add? (and right-action? (not-empty val))
                        add-next (when can-add?
                                   #(do (dispatch-sync [::extend-string-answer
                                                        article-id root-label-id label-id ith curvals])
                                        (focus-next)))
                        add-next-handler (util/wrap-user-event add-next
                                                               :prevent-default true
                                                               :stop-propagation true
                                                               :timeout false)
                        can-delete? (not (and (= i 0) (= nvals 1) (empty? val)))
                        delete-current #(do (dispatch-sync [::remove-string-value
                                                            article-id root-label-id label-id ith i curvals])
                                            (focus-prev))]
                    ^{:key [root-label-id label-id ith i]}
                    [:div.ui.small.form.string-label
                     [:div.field.string-label {:class (css [(empty? val) ""
                                                            valid?       "success"
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
                                :on-change (util/on-event-value
                                            #(dispatch-sync [::set-string-value
                                                             article-id root-label-id label-id ith i % curvals]))
                                :on-key-down #(cond (= "Enter" (.-key %))
                                                    (if add-next (add-next) (focus-next))
                                                    (and (in? ["Backspace" "Delete" "Del"] (.-key %))
                                                         (empty? val) can-delete?)
                                                    (delete-current)
                                                    :else true)}]
                       (when right-action?
                         [:div.ui.icon.button.input-row {:class (css [(not can-add?) "disabled"])
                                                         :on-click add-next-handler}
                          [:i.plus.icon]])]]
                     (when (and (not valid?)
                                (not (clojure.string/blank? val)))
                       (dispatch [:review/create-invalid-label article-id root-label-id label-id ith])
                       [Message {:color "red"} "Invalid Value"])
                     ;; check that the labels are no longer invalid, clear if they're not
                     (when (if-let [validated-vals (->> curvals
                                                        (filter (comp not clojure.string/blank?))
                                                        (map (fn [val] @(subscribe [:label/valid-string-value? root-label-id label-id val]))))]
                             (every? true? validated-vals)
                             true)
                       ;; when every value is valid, clear out invalid label setting for this label
                       (dispatch [:review/delete-invalid-label article-id root-label-id label-id ith]))])))))])))
      :component-will-unmount (fn [_]
                                (dispatch [:review/delete-invalid-label article-id root-label-id label-id ith]))})))

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

(defn label-help-popup [{:keys [category required question definition]}]
  (let [criteria? (= category "inclusion criteria")
        required? required
        examples (:examples definition)]
    [:div.ui.inverted.grid.popup.transition.hidden.label-help
     {:on-click (util/wrap-user-event #(do nil))}
     [:div.middle.aligned.center.aligned.row.label-help-header
      [:div.ui.sixteen.wide.column
       [:span {:style {:font-size "110%"}}
        (str (cond (and criteria? required?)
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

(defn GroupLabelInput
  [label-id article-id]
  (let [label-name @(subscribe [:label/display "na" label-id "na"])
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
                               :padding-right "0.5em"}} (str (count (:labels @answers)) (util/pluralize (count (:labels @answers)) " Row"))]]))

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
  (let [value-type @(subscribe [:label/value-type "na" label-id "na"])
        label-css-class @(subscribe [::label-css-class article-id label-id])
        label-string @(subscribe [:label/display "na" label-id "na"])
        question @(subscribe [:label/question "na" label-id "na"])
        on-click-help (util/wrap-user-event #(do nil) :timeout false)
        answer (subscribe [:review/active-labels article-id "na" label-id "na"])]
    [:div.ui.column.label-edit {:class label-css-class}
     [:div.ui.middle.aligned.grid.label-edit
      [ui/with-tooltip
       (let [name-content
             [:span.name
              {:class (css [(>= (count label-string) 30) "small-text"])}
              [:span.inner label-string]]]
         (if (and (util/mobile?) (>= (count label-string) 30))
           [:div.ui.row.label-edit-name {:on-click on-click-help}
            [inclusion-tag article-id "na" label-id "na"]
            [:span.name " "]
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])
            [:div.clear name-content]]
           [:div.ui.row.label-edit-name {:on-click on-click-help
                                         :style {:cursor "help"}}
            [inclusion-tag article-id "na" label-id "na"]
            name-content
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])]))
       {:variation "basic"
        :delay {:show 350, :hide 25}
        :duration 100
        :hoverable false
        :inline true
        :position (if (= n-cols 1)
                    (if (<= label-position 1) "bottom center" "top center")
                    (cond (= row-position :left)   "top left"
                          (= row-position :right)  "top right"
                          :else                    "top center"))
        :distanceAway 5}]
      [label-help-popup {:category @(subscribe [:label/category "na" label-id "na"])
                         :required @(subscribe [:label/required? "na" label-id "na"])
                         :question @(subscribe [:label/question "na" label-id "na"])
                         :definition {:examples @(subscribe [:label/examples "na" label-id "na"])}}]
      [:div.ui.row.label-edit-value {:class (condp = value-type
                                              "boolean"      "boolean"
                                              "categorical"  "category"
                                              "string"       "string"
                                              "")}
       [:div.inner (condp = value-type
                     "boolean" [BooleanLabelInput ["na" label-id "na"] article-id]
                     "categorical" [CategoricalLabelInput ["na" label-id "na"] article-id]
                     "string" [StringLabelInput ["na" label-id "na"] article-id]
                     [:div "unknown label - label-column"])]]]
     (if (and (not= value-type "group")
              @(subscribe [:label/required? "na" label-id])
              (not @(subscribe [:label/non-empty-answer? "na" label-id @answer])))
       (do
         (dispatch [:review/create-missing-label article-id "na" label-id "na"])
         [:div {:style {:text-align "center"
                        :margin-bottom "0.5rem"}
                :class  "missing-label-answer"
                :div (str "missing-label-answer " label-id)} "Required"])
       (dispatch [:review/delete-missing-label article-id "na" label-id "na"]))]))

(defn- note-input-element [note-name]
  (when @(subscribe [:project/notes nil note-name])
    (let [article-id @(subscribe [:review/editing-id])
          note-description @(subscribe [:note/description note-name])
          note-content @(subscribe [:review/active-note article-id note-name])]
      [:div.ui.segment.notes>div.ui.middle.aligned.form.notes>div.middle.aligned.field.notes
       [:label.middle.aligned.notes
        note-description [:i.large.middle.aligned.grey.write.icon]]
       [:textarea {:type "text"
                   :rows 2
                   :name note-name
                   :value (or note-content "")
                   :on-change #(let [content (-> % .-target .-value)]
                                 (dispatch-sync [:review/set-note-content
                                                 article-id note-name content]))}]])))

(defn- ActivityReport []
  (when-let [today-count @(subscribe [:review/today-count])]
    (if (util/full-size?)
      [:div.ui.label.activity-report
       [:span.ui.green.circular.label today-count]
       [:span.text nbsp nbsp "finished today"]]
      [:div.ui.label.activity-report
       [:span.ui.tiny.green.circular.label today-count]
       [:span.text nbsp nbsp "today"]])))

(defn- review-task-saving? [article-id]
  (and @(subscribe [:review/saving? article-id])
       (or (loading/any-action-running? :only :review/send-labels)
           (loading/any-loading? :only :article)
           (loading/any-loading? :only :review/task))))

(defn- review-task-ready-for-action? []
  (and (loading/ajax-status-inactive? 50)
       (zero? (-> ($ "div.view-pdf.rendering") .-length))
       (zero? (-> ($ "div.view-pdf.updating") .-length))))

(defn SaveButton [article-id & [small? fluid?]]
  (let [project-id @(subscribe [:active-project-id])
        resolving? @(subscribe [:review/resolving?])
        on-review-task? (subscribe [:review/on-review-task?])
        review-task-id @(subscribe [:review/task-id])
        missing @(subscribe [:review/missing-labels article-id])
        invalid @(subscribe [:review/invalid-labels article-id])
        saving? (review-task-saving? article-id)
        disabled? (or (seq missing) (seq invalid))
        project-articles-id @(subscribe [:project-articles/article-id])
        on-save (util/wrap-user-event
                 (fn []
                   (util/run-after-condition
                    [:review-save article-id]
                    review-task-ready-for-action?
                    (fn []
                      (when-not (loading/any-action-running? :only :review/send-labels)
                        (dispatch-sync [:review/mark-saving article-id])
                        (sync-article-notes article-id)
                        (dispatch
                         [:review/send-labels
                          {:project-id project-id
                           :article-id article-id
                           :confirm? true
                           :resolve? (boolean resolving?)
                           :on-success (concat
                                        (when (or @on-review-task? (= article-id review-task-id))
                                          [[:fetch [:review/task project-id]]])
                                        (when (not @on-review-task?)
                                          [[:fetch [:article project-id article-id]]
                                           [:review/disable-change-labels article-id]])
                                        (when project-articles-id
                                          [[:project-articles/reload-list]
                                           [:project-articles/hide-article]
                                           [:scroll-top]]))}]))))))
        button (fn [] [:button.ui.right.labeled.icon.button.save-labels
                       {:class (css [(or disabled? saving?) "disabled"]
                                    [saving? "loading"]
                                    [small? "tiny"]
                                    [fluid? "fluid"]
                                    [resolving? "purple" :else "primary"])
                        :on-click (when-not (or disabled? saving?) on-save)}
                       (str (if resolving? "Resolve" "Save") (when-not small? "Labels"))
                       [:i.check.circle.outline.icon]])]
    (list (if disabled?
            ^{:key :save-button} [ui/with-tooltip [:div [button]]]
            ^{:key :save-button} [button])
          ^{:key :save-button-popup}
          [:div.ui.inverted.popup.top.left.transition.hidden
           {:style {:min-width "20em"}}
           [:ul {:style {:padding-left "1.25em"}}
            (when (seq missing)
              [:li "Answer missing for a required label"])
            (when (seq invalid)
              [:li "Invalid label answer(s)"])]])))

(defn SkipArticle [article-id & [small? fluid?]]
  (let [project-id @(subscribe [:active-project-id])
        saving? (review-task-saving? article-id)
        on-review-task? (subscribe [:review/on-review-task?])
        loading-task? (and (not saving?)
                           @on-review-task?
                           (loading/item-loading? [:review/task project-id]))
        on-click (util/wrap-user-event
                  (fn []
                    (util/run-after-condition
                     [:review-skip article-id]
                     review-task-ready-for-action?
                     (fn []
                       (when @on-review-task?
                         (sync-article-notes article-id)
                         ;; skip article should not SAVE labels!
                         #_ (dispatch [:review/send-labels {:project-id project-id
                                                            :article-id article-id
                                                            :confirm? false
                                                            :resolve? false}])
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

;; Component for row of action buttons below label inputs grid
(defn- label-editor-buttons-view [article-id]
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

(defn make-label-columns [article-id label-ids n-cols]
  (doall (for [row (partition-all n-cols label-ids)]
           ^{:key [(first row)]}
           [:div.row
            (doall (for [i (range (count row))]
                     (let [label-id (nth row i)
                           row-position (cond (= i 0)            :left
                                              (= i (dec n-cols)) :right
                                              :else              :middle)
                           label-position (->> label-ids (take-while #(not= % label-id)) count)]
                       (if (= "group" @(subscribe [:label/value-type "na" label-id "na"]))
                         ^{:key {:article-label [article-id label-id]}}
                         [GroupLabelColumn article-id label-id row-position n-cols label-position]
                         ^{:key {:article-label [article-id label-id]}}
                         [LabelColumn article-id label-id row-position n-cols label-position]))))
            (when (< (count row) n-cols) [:div.column])])))

(defn LabelsColumns [article-id & {:keys [n-cols class] :or {class "segment"}}]
  (let [n-cols (or n-cols (cond (util/full-size?) 4
                                (util/mobile?)    2
                                :else             3))
        label-ids @(subscribe [:project/label-ids])]
    [:div.label-section
     {:class (css "ui" (util/num-to-english n-cols) "column celled grid" class)}
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

;; Top-level component for label editor
(defn LabelAnswerEditor [article-id]
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
             [note-input-element "default"]
             (when-not (display-sidebar?)
               [label-editor-buttons-view article-id])]))))))

(defn LabelAnswerEditorColumn [_article-id]
  (r/create-class
   {:component-did-mount #(util/update-sidebar-height)
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

