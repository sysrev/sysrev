(ns sysrev.views.review
  (:require ["jquery" :as $]
            ["fomantic-ui"]
            [clojure.string :as str]
            [goog.string :as gstr]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-sub
                                   reg-event-db reg-event-fx reg-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.state.review :as review]
            [sysrev.state.label :refer [get-label-raw]]
            [sysrev.state.note :refer [sync-article-notes]]
            [sysrev.views.components.core :as ui]
            [sysrev.util :as util :refer [format nbsp]]
            [sysrev.shared.util :as sutil :refer [in? css]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn set-label-value [db article-id label-id label-value]
  (assoc-in db [:state :review :labels article-id label-id]
            label-value))

(reg-event-db ::set-label-value [trim-v]
              (fn [db [article-id label-id label-value]]
                (set-label-value db article-id label-id label-value)))

;; Adds a value to an active label answer vector
(reg-event-db ::add-label-value [trim-v]
              (fn [db [article-id label-id label-value]]
                (let [current-values (get (review/active-labels db article-id) label-id)]
                  (set-label-value db article-id label-id
                                   (-> current-values (concat [label-value]) distinct vec)))))

;; Removes a value from an active label answer vector
(reg-event-db ::remove-label-value [trim-v]
              (fn [db [article-id label-id label-value]]
                (let [current-values (get (review/active-labels db article-id) label-id)]
                  (set-label-value db article-id label-id
                                   (->> current-values (remove (partial = label-value)) vec)))))

;; Triggers "set selected" Semantic dropdown action
(reg-fx ::select-categorical-value
        (fn [[article-id label-id label-value]]
          (.dropdown ($ (str "#label-edit-" article-id "-" label-id))
                     "set selected" label-value)))

(reg-event-db ::remove-string-value [trim-v]
              (fn [db [article-id label-id value-idx curvals]]
                (set-label-value db article-id label-id
                                 (->> (assoc (vec curvals) value-idx "")
                                      (filterv not-empty)))))

(reg-event-db ::set-string-value [trim-v]
              (fn [db [article-id label-id value-idx label-value curvals]]
                (set-label-value db article-id label-id
                                 (assoc (vec curvals) value-idx label-value))))

(reg-event-db ::extend-string-answer [trim-v]
              (fn [db [article-id label-id curvals]]
                (set-label-value db article-id label-id
                                 (assoc (vec curvals) (count curvals) ""))))

;; Simulates an "enable value" label input component event
(reg-event-fx :review/trigger-enable-label-value [trim-v]
              (fn [{:keys [db]} [article-id label-id label-value]]
                (let [{:keys [value-type]} (get-label-raw db label-id)]
                  (condp = value-type
                    "boolean"      {:db (set-label-value db article-id label-id label-value)}
                    "categorical"  {::select-categorical-value [article-id label-id label-value]}))))

;; Renders input component for label
(defmulti label-input-el
  (fn [label-id _article-id] @(subscribe [:label/value-type label-id])))

(defmethod label-input-el "boolean"
  [label-id article-id]
  (let [answer (subscribe [:review/active-labels article-id label-id])]
    [ui/three-state-selection
     {:set-answer! #(dispatch [::set-label-value article-id label-id %])
      :value answer}]))

(defmethod label-input-el "categorical"
  [label-id article-id]
  (let [dom-class (str "label-edit-" article-id "-" label-id)
        input-name (str "label-edit(" dom-class ")")
        dom-q (str "." dom-class ":visible")
        _input-q (str dom-q " input[type!='hidden']")
        ensure-values-loaded
        (fn [this]
          (let [^js node ($ (r/dom-node this))
                _ (.dropdown node "refresh")
                active-vals (vec @(subscribe [:review/active-labels article-id label-id]))
                comp-vals (-> (.dropdown node "get value")
                              gstr/unescapeEntities
                              (str/split #",")
                              ((partial filterv not-empty)))]
            #_ (util/log
                (->> ["---------------------------------"
                      (format "comp-vals = %s" (pr-str comp-vals))
                      (format "active-vals = %s" (pr-str active-vals))
                      "---------------------------------"]
                     (str/join "\n")))
            (when (not= comp-vals active-vals)
              (let [active-str (->> active-vals (str/join ","))]
                #_ (util/log
                    (->> [(format "setting dropdown dom values: %s" (pr-str active-str))
                          "---------------------------------"]
                         (str/join "\n")))
                (.dropdown node "clear" true)
                (.dropdown node "set exactly" active-str)))))]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (let [node #($ (r/dom-node this))]
          (->> {:duration 125
                :onAdd     (fn [v _t]
                             (let [val (gstr/unescapeEntities v)]
                               #_ (util/log "onAdd: %s" (pr-str val))
                               (dispatch [::add-label-value article-id label-id val])
                               (.dropdown (node) "hide")))
                :onRemove  (fn [v _t]
                             (let [val (gstr/unescapeEntities v)]
                               #_ (util/log "onRemove: %s" (pr-str val))
                               (dispatch [::remove-label-value article-id label-id val])))
                :onChange  (fn [& _args]
                             #_ (util/log "onChange: %s" (pr-str _args))
                             #_ (.dropdown (node) "hide"))}
               (clj->js)
               (.dropdown (node))))
        (ensure-values-loaded this))
      :component-will-update
      (fn [this]
        (ensure-values-loaded this))
      :reagent-render
      (fn [label-id article-id]
        (when (= article-id @(subscribe [:review/editing-id]))
          (let [required? @(subscribe [:label/required? label-id])
                all-values (as-> @(subscribe [:label/all-values label-id]) vs
                             (if (every? string? vs)
                               (concat
                                (->> vs (filter #(in? ["none" "other"] (str/lower-case %))))
                                (->> vs (remove #(in? ["none" "other"] (str/lower-case %)))
                                     (sort #(compare (str/lower-case %1)
                                                     (str/lower-case %2)))))
                               vs))
                current-values @(subscribe [:review/active-labels article-id label-id])
                touchscreen? @(subscribe [:touchscreen?])]
            [(if touchscreen?
               :div.ui.small.fluid.multiple.selection.dropdown
               :div.ui.small.fluid.search.selection.dropdown.multiple)
             {:key [:dropdown dom-class]
              :class dom-class
              :on-click
              ;; remove label elements on click anywhere on label
              (util/wrap-user-event
               #(let [target ($ (.-target %))]
                  (when (and (.hasClass target "label")
                             ((every-pred string? not-empty)
                              (.attr target "data-value")))
                    (let [v (.attr target "data-value")
                          node ($ dom-q)]
                      #_ (util/log "removing value: %s" (-> v gstr/unescapeEntities pr-str))
                      (.dropdown node "remove selected" v)
                      (when (.dropdown node "is visible")
                        (.dropdown node "hide"))))))}
             [:input {:name input-name
                      :value (str/join "," current-values)
                      :type "hidden"}]
             [:i.dropdown.icon]
             (if required?
               [:div.default.text "No answer selected " [:span.default.bold "(required)"]]
               [:div.default.text "No answer selected"])
             [:div.menu
              (doall (for [[i lval] (map-indexed vector all-values)]
                       (let [v (-> lval gstr/htmlEscape)] ^{:key [i]}
                         [:div.item {:data-value v} lval])))]])))})))

(defmethod label-input-el "string"
  [label-id article-id]
  (let [curvals (or (not-empty @(subscribe [:review/active-labels article-id label-id]))
                    [""])
        multi? @(subscribe [:label/multi? label-id])
        nvals (count curvals)
        class-for-idx #(str label-id "__value_" %)]
    (when (= article-id @(subscribe [:review/editing-id]))
      [:div.inner
       (doall
        (->>
         curvals
         (map-indexed
          (fn [i val]
            (let [left-action? true
                  right-action? (and multi? (= i (dec nvals)))
                  valid? @(subscribe [:label/valid-string-value? label-id val])
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
                                                  article-id label-id curvals])
                                  (focus-next)))
                  add-next-handler (util/wrap-user-event add-next
                                                         :prevent-default true
                                                         :stop-propagation true
                                                         :timeout false)
                  can-delete? (not (and (= i 0) (= nvals 1) (empty? val)))
                  delete-current #(do (dispatch-sync [::remove-string-value
                                                      article-id label-id i curvals])
                                      (focus-prev))]
              ^{:key [label-id i]}
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
                                                       article-id label-id i % curvals]))
                          :on-key-down #(cond (= "Enter" (.-key %))
                                              (if add-next (add-next) (focus-next))
                                              (and (in? ["Backspace" "Delete" "Del"] (.-key %))
                                                   (empty? val) can-delete?)
                                              (delete-current)
                                              :else true)}]
                 (when right-action?
                   [:div.ui.icon.button.input-row {:class (css [(not can-add?) "disabled"])
                                                   :on-click add-next-handler}
                    [:i.plus.icon]])]]])))))])))

(defn- inclusion-tag [article-id label-id]
  (let [criteria? @(subscribe [:label/inclusion-criteria? label-id])
        answer @(subscribe [:review/active-labels article-id label-id])
        inclusion @(subscribe [:label/answer-inclusion label-id answer])]
    [:i.left.floated.fitted {:class (css [(not criteria?)     "grey content"
                                          (true? inclusion)   "green circle plus"
                                          (false? inclusion)  "orange circle minus"
                                          (nil? inclusion)    "grey circle outline"]
                                         "icon")}]))

(defn label-help-popup [label]
  (let [{:keys [category required question definition]} label
        criteria? (= category "inclusion criteria")
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

(reg-sub ::label-css-class
         (fn [[_ article-id label-id]]
           [(subscribe [:review/inconsistent-labels article-id label-id])
            (subscribe [:label/required? label-id])
            (subscribe [:label/inclusion-criteria? label-id])])
         (fn [[inconsistent? required? criteria?]]
           (cond inconsistent?   "inconsistent"
                 required?       "required"
                 (not criteria?) "extra"
                 :else           "")))

;; Component for label column in inputs grid
(defn- label-column [article-id label-id row-position n-cols label-position _n-labels]
  (let [value-type @(subscribe [:label/value-type label-id])
        label-css-class @(subscribe [::label-css-class article-id label-id])
        label-string @(subscribe [:label/display label-id])
        question @(subscribe [:label/question label-id])
        on-click-help (util/wrap-user-event
                       #(do nil) :timeout false)]
    ^{:key {:article-label [article-id label-id]}}
    [:div.ui.column.label-edit {:class label-css-class}
     [:div.ui.middle.aligned.grid.label-edit
      [ui/with-tooltip
       (let [name-content
             [:span.name
              {:class (css [(>= (count label-string) 30) "small-text"])}
              [:span.inner label-string]]]
         (if (and (util/mobile?) (>= (count label-string) 30))
           [:div.ui.row.label-edit-name {:on-click on-click-help}
            [inclusion-tag article-id label-id]
            [:span.name " "]
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])
            [:div.clear name-content]]
           [:div.ui.row.label-edit-name {:on-click on-click-help
                                         :style {:cursor "help"}}
            [inclusion-tag article-id label-id]
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
      [label-help-popup {:category @(subscribe [:label/category label-id])
                         :required @(subscribe [:label/required? label-id])
                         :question @(subscribe [:label/question label-id])
                         :definition {:examples @(subscribe [:label/examples label-id])}}]
      [:div.ui.row.label-edit-value {:class (case value-type
                                              "boolean"      "boolean"
                                              "categorical"  "category"
                                              "string"       "string"
                                              "")}
       [:div.inner [label-input-el label-id article-id]]]]]))

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

(defn SaveButton [article-id & [small? fluid?]]
  (let [project-id @(subscribe [:active-project-id])
        resolving? @(subscribe [:review/resolving?])
        on-review-task? @(subscribe [:review/on-review-task?])
        review-task-id @(subscribe [:review/task-id])
        missing @(subscribe [:review/missing-labels article-id])
        disabled? (not-empty missing)
        saving? (and @(subscribe [:review/saving? article-id])
                     (or (loading/any-action-running? :only :review/send-labels)
                         (loading/any-loading? :only :article)
                         (loading/any-loading? :only :review/task)))
        save-class (css [disabled? "disabled"] [saving? "loading"] [small? "tiny"] [fluid? "fluid"]
                        [resolving? "purple" :else "primary"])
        on-save (util/wrap-user-event
                 (fn []
                   (util/run-after-condition
                    [:review-save article-id]
                    #(and (loading/ajax-status-inactive? 50)
                          (nil? (aget ($ "div.view-pdf.rendering") 0))
                          (nil? (aget ($ "div.view-pdf.updating") 0)))
                    (fn []
                      (sync-article-notes article-id)
                      (dispatch
                       [:review/send-labels
                        {:project-id project-id
                         :article-id article-id
                         :confirm? true
                         :resolve? (boolean resolving?)
                         :on-success (->> [(when (or on-review-task? (= article-id review-task-id))
                                             [:fetch [:review/task project-id]])
                                           (when (not on-review-task?)
                                             [:fetch [:article project-id article-id]])
                                           (when (not on-review-task?)
                                             [:review/disable-change-labels article-id])
                                           (when @(subscribe [:project-articles/article-id])
                                             (dispatch [:project-articles/reload-list])
                                             (dispatch [:project-articles/hide-article])
                                             (util/scroll-top))]
                                          (remove nil?))}])))))
        button (fn [] [:button.ui.right.labeled.icon.button.save-labels
                       {:class save-class, :on-click on-save}
                       (str (if resolving? "Resolve" "Save") (when-not small? "Labels"))
                       [:i.check.circle.outline.icon]])]
    (list (if disabled?
            ^{:key :save-button} [ui/with-tooltip [:div [button article-id]]]
            ^{:key :save-button} [button article-id])
          ^{:key :save-button-popup}
          [:div.ui.inverted.popup.top.left.transition.hidden
           "Answer missing for a required label"])))

(defn SkipArticle [article-id & [small? fluid?]]
  (let [saving? (and @(subscribe [:review/saving? article-id])
                     (or (loading/any-action-running? :only :review/send-labels)
                         (loading/any-loading? :only :article)
                         (loading/any-loading? :only :review/task)))

        project-id @(subscribe [:active-project-id])
        loading-task? (and (not saving?)
                           @(subscribe [:review/on-review-task?])
                           (loading/item-loading? [:review/task project-id]))
        on-review-task? @(subscribe [:review/on-review-task?])
        on-next (util/wrap-user-event
                 (fn []
                   (util/run-after-condition
                    [:review-skip article-id]
                    #(and (loading/ajax-status-inactive? 50)
                          (nil? (aget ($ "div.view-pdf.rendering") 0))
                          (nil? (aget ($ "div.view-pdf.updating") 0)))
                    #(when on-review-task?
                       (sync-article-notes article-id)
                       (dispatch [:review/send-labels {:project-id project-id
                                                       :article-id article-id
                                                       :confirm? false
                                                       :resolve? false}])
                       (dispatch [:fetch [:review/task project-id]])))))]
    (list ^{:key :skip-article}
          [:button.ui.right.labeled.icon.button.skip-article
           {:class (css [loading-task? "loading"] [small? "tiny"] [fluid? "fluid"])
            :on-click on-next}
           (if (and (util/full-size?) (not small?)) "Skip Article" "Skip")
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
                     (let [label-id (nth row i)]
                       (label-column article-id
                                     label-id
                                     (cond (= i 0)            :left
                                           (= i (dec n-cols)) :right
                                           :else              :middle)
                                     n-cols
                                     (->> label-ids (take-while #(not= % label-id)) count)
                                     (count label-ids)))))
            (when (< (count row) n-cols) [:div.column])])))

(defn LabelsColumns [article-id & {:keys [n-cols class] :or {class "segment"}}]
  (let [n-cols (or n-cols (cond (util/full-size?) 4
                                (util/mobile?)    2
                                :else             3))
        label-ids @(subscribe [:project/label-ids])]
    [:div.label-section
     {:class (css "ui" (sutil/num-to-english n-cols) "column celled grid" class)}
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
