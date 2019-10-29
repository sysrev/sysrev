(ns sysrev.views.panels.project.define-labels
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.label :refer [sort-client-project-labels]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.review :refer [label-help-popup]]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.dnd :as dnd]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? map-values map-kv css]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; Convention -
;; A (new) label that exists in the client but not on the
;; server has a label-id of type string.
;; After label is saved to server the label-id type is uuid.

;; The jQuery plugin formBuilder is inspiration for the UI
;; repo: https://github.com/kevinchappell/formBuilder
;; demo: https://jsfiddle.net/kevinchappell/ajp60dzk/5/

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :labels :edit] {:state-var state})

(def initial-state {:read-only-message-closed? false})

(defn- saved-labels
  "Get the saved label values for the active project"
  []
  @(subscribe [:project/labels-raw]))

(defn to-local-labels
  "Convert labels map to format used by local namespace state."
  [labels]
  (let [insert-answer #(assoc % :answer (case (:value-type %)
                                          "boolean" nil
                                          []))
        add-local-keys #(assoc % :editing? (not-empty (:errors %)))
        set-inclusion #(cond-> %
                         (not (contains? % :inclusion))
                         (assoc :inclusion (pos? (-> % :definition :inclusion-values count))))]
    (map-values (comp insert-answer add-local-keys set-inclusion) labels)))

(defn set-local-labels!
  "Sets value of local labels state from labels map, applying some
  conversions to format correctly for local state."
  [labels]
  (swap! state assoc-in [:labels] (to-local-labels labels)))

(defn ensure-state []
  (when (nil? @state)              (reset! state initial-state))
  (when (empty? (:labels @state))  (set-local-labels! (saved-labels))))

(defn- get-local-labels []
  (get-in @state [:labels]))

(defn max-project-ordering
  "Obtain the max-project-ordering from the local state of labels"
  []
  (apply max (map :project-ordering (vals (get-local-labels)))))

(defn default-answer [value-type]
  (case value-type
    "boolean"      nil
    "string"       []
    "categorical"  []
    nil))

(defn move-label-to
  "Reorder labels, moving `src-label-id` to the current position of
  `dest-label-id` and shifting others as needed."
  [src-label-id dest-label-id]
  (when (not= (str src-label-id) (str dest-label-id))
    (let [labels (get-local-labels)
          src-label (or (get labels src-label-id)
                        (get labels (uuid src-label-id)))
          dest-label (or (get labels dest-label-id)
                         (get labels (uuid dest-label-id)))
          src-pos (:project-ordering src-label)
          dest-pos (:project-ordering dest-label)]
      (when-not (or (= (:name dest-label) "overall include")
                    (= (:name src-label) "overall include"))
        (->> (reset! (r/cursor state [:labels])
                     (map-kv (fn [label-id label]
                               (let [this-pos (:project-ordering label)]
                                 [label-id
                                  (cond
                                    ;; update src-label, set ordering from dest-label
                                    (= (str label-id) (str src-label-id))
                                    (assoc label :project-ordering dest-pos)
                                    ;; moving src-label closer to start;
                                    ;; increment values between src and dest to make room
                                    (and (< dest-pos src-pos)
                                         (>= this-pos dest-pos)
                                         (< this-pos src-pos))
                                    (update label :project-ordering inc)
                                    ;; moving src-label closer to end;
                                    ;; decrement values between src and dest to make room
                                    (and (> dest-pos src-pos)
                                         (<= this-pos dest-pos)
                                         (> this-pos src-pos))
                                    (update label :project-ordering dec)
                                    ;; leave other labels unchanged
                                    :else label)]))
                             labels))
             (map-values #(select-keys % [:project-ordering :short-label])))))))

(defn create-blank-label [value-type]
  (let [label-id (str "new-label-" (sutil/random-id))]
    {:definition (case value-type
                   "boolean"      {:inclusion-values []}
                   "string"       {:multi? false :max-length 100}
                   "categorical"  {:inclusion-values [] :multi? true}
                   {})
     :inclusion false
     :category "extra"
     :name (str value-type (sutil/random-id))
     :project-ordering (inc (max-project-ordering))
     :label-id label-id ;; this is a string, to distinguish unsaved labels
     :project-id (active-project-id @app-db)
     :enabled true
     :value-type value-type
     :required false
     ;;; these last fields are used only internally by this namespace;
     ;;; filtered before exporting elsewhere
     :answer (default-answer value-type)
     :editing? true
     :errors (list)}))

(defn add-new-label!
  "Add a new label in local namespace state."
  [label]
  (swap! state assoc-in [:labels (:label-id label)] label))

(defn errors-in-labels? [labels]
  (not (every? nil? (map :errors (vals labels)))))

(defn to-global-labels
  "Convert labels map to format used by global client state and server."
  [labels]
  (->> (vals labels)
       ;; FIX: run db migration to fix label `category` values;
       ;; may affect label tooltips and sorting
       (map (fn [{:keys [definition] :as label}]
              (let [{:keys [inclusion-values]} definition]
                ;; set correct `category` value based on `inclusion-values`
                (assoc label :category
                       (if (not-empty inclusion-values)
                         "inclusion criteria" "extra")))))
       (map #(dissoc % :editing? :answer :errors :inclusion))
       (map #(hash-map (:label-id %) %))
       (apply merge)))

(defn labels-synced?
  "Are the labels synced with the global app-db?"
  []
  (= (saved-labels) (to-global-labels (get-local-labels))))

(defn single-label-synced?
  [label-id]
  (= (get (saved-labels) label-id)
     (get (to-global-labels (get-local-labels)) label-id)))

(defn sync-to-server
  "Send local labels to server to update DB."
  []
  (when (not (labels-synced?))
    (dispatch [:action [:labels/sync-project-labels
                        (active-project-id @app-db)
                        (to-global-labels (get-local-labels))]])))

(defn set-app-db-labels!
  "Overwrite the value of global project labels map."
  [labels]
  (let [project-id (active-project-id @app-db)
        app-labels (r/cursor app-db [:data :project project-id :labels])]
    (reset! app-labels labels)))

(defn reset-local-label!
  "Resets local state of label to saved state, or discards unsaved."
  [label-id]
  (if (string? label-id)
    (swap! (r/cursor state [:labels]) dissoc label-id)
    (reset! (r/cursor state [:labels label-id])
            (-> (saved-labels) to-local-labels (get label-id)))))

(defn DisableEnableLabelButton [label]
  (let [{:keys [label-id enabled]} @label]
    (when-not (string? label-id) ;; don't show this for unsaved labels
      [:button.ui.small.fluid.labeled.icon.button
       {:class (css [(not enabled) "primary"])
        :on-click (util/wrap-user-event #(do (reset-local-label! label-id)
                                             (swap! (r/cursor label [:enabled]) not)
                                             (sync-to-server))
                                        :prevent-default true)}
       [:i {:class (css "circle" [enabled "minus" :else "plus"] "icon")}]
       (if enabled "Disable" "Enable") " Label"])))

(defn CancelDiscardButton [label]
  (let [{:keys [label-id]} @label
        text (if (string? label-id) "Discard" "Cancel")]
    [:button.ui.small.fluid.labeled.icon.button
     {:on-click (util/wrap-user-event #(reset-local-label! label-id)
                                      :prevent-default true)}
     [:i.circle.times.icon] text]))

(defn save-request-active? []
  (loading/any-action-running? :only :labels/sync-project-labels))

(defn SaveLabelButton [_label]
  [:button.ui.small.fluid.positive.labeled.icon.button
   {:type "submit"
    :class (css [(save-request-active?) "loading"]
                [(labels-synced?) "disabled"])}
   [:i.check.circle.outline.icon] "Save"])

(defn EditLabelButton
  "label is a cursor into the state representing the label"
  [label allow-edit?]
  (let [{:keys [editing?]} @label
        synced? (labels-synced?)]
    [:div.ui.small.icon.button.edit-label-button
     {:class (css [(not allow-edit?) "disabled"])
      :style {:margin-left 0 :margin-right 0}
      :on-click (util/wrap-user-event #(do (when editing? (sync-to-server))
                                           (swap! (r/cursor label [:editing?]) not)))}
     [:i {:class (css [(not editing?) "edit"
                       (not synced?)  "green circle check"
                       :else          "circle check"]
                      "icon")}]]))

(defn- AddLabelButton [value-type]
  [:button.ui.fluid.large.labeled.icon.button
   {:on-click (util/wrap-user-event #(add-new-label! (create-blank-label value-type)))}
   [:i.plus.circle.icon]
   (str "Add " (str/capitalize value-type) " Label")])

(def-action :labels/sync-project-labels
  :uri (fn [] "/api/sync-project-labels")
  :content (fn [project-id labels] {:project-id project-id :labels labels})
  :process (fn [_ _ {:keys [valid? labels]}]
             (if valid? ;; update successful?
               ;; update (1) app-wide project data and (2) local namespace state
               (do (set-app-db-labels! labels)
                   (set-local-labels! labels))
               ;; update local state (includes error messages)
               (set-local-labels! labels))
             {}))

(defn InclusionTag [{:keys [category answer definition value-type]}]
  (let [{:keys [inclusion-values]} definition
        criteria? (= category "inclusion criteria")
        inclusion (if (or (nil? answer) (empty? inclusion-values)) nil
                      (case value-type
                        "boolean"      (boolean (in? inclusion-values answer))
                        "categorical"  (if (empty? answer) nil
                                           (boolean (some (in? inclusion-values) answer)))
                        nil))]
    [:i {:class (css "left floated fitted" [(not criteria?)     "grey content"
                                            (true? inclusion)   "green circle plus"
                                            (false? inclusion)  "orange circle minus"
                                            :else               "grey circle outline"]
                     "icon")}]))

(defn FormLabelWithTooltip [text tooltip-content]
  (doall (ui/with-ui-help-tooltip
           [:label text " " [ui/ui-help-icon]]
           :help-content tooltip-content
           :popup-options {:delay {:show 500 :hide 0}})))

(def label-settings-config
  {:short-label  {:display "Name"}
   :question     {:display "Question"
                  :tooltip ["Describe the meaning of this label for reviewers."
                            "Displayed as tooltip in review interface."]}
   :required     {:display "Require answer"
                  :tooltip ["Require users to provide an answer for this label before saving article."]}
   :consensus    {:display "Require user consensus"
                  :tooltip ["Check answers for consensus among users."
                            "Articles will be marked as conflicted if user answers are not identical."]}
   :max-length   {:path [:definition :max-length]
                  :display "Max length"}
   :examples     {:path [:definition :examples]
                  :display "Examples (comma-separated)"
                  :tooltip ["Examples of possible label values for reviewers."
                            "Displayed as tooltip in review interface."]
                  :optional true}
   :all-values   {:path [:definition :all-values]
                  :display "Categories (comma-separated options)"
                  :tooltip ["List of values allowed for label."
                            "Reviewers may select multiple values in their answers."]
                  :placeholder "one,two,three"}
   :multi?       {:path [:definition :multi?]
                  :display "Allow multiple values"
                  :tooltip ["Allow answers to contain multiple string values."]}
   :inclusion    {:display "Inclusion criteria"
                  :tooltip ["Define a relationship between this label and article inclusion."
                            "Users will be warned if their answers contradict the value selected for article inclusion."]}})

(defn- label-setting-field-args
  "Creates map of standard arguments to field component function for a
  label setting (i.e. entry in label-settings-config)."
  [setting & [errors extra]]
  (when-let [{:keys [path display tooltip placeholder optional]}
             (get label-settings-config setting)]
    (cond-> {:field-class (-> (str "field-" (name setting))
                              (str/split #"\?") ;; remove ? from css class
                              (str/join))}
      errors       (assoc :error (get-in errors (or path [setting])))
      display      (assoc :label display)
      tooltip      (assoc :tooltip tooltip)
      placeholder  (assoc :placeholder placeholder)
      optional     (assoc :optional optional)
      extra        (merge extra))))

(defn LabelEditForm [label]
  (let [show-error-msg #(when % [:div.ui.red.message %])
        value-type (r/cursor label [:value-type])
;;; all types
        ;; required, string
        short-label (r/cursor label [:short-label])
        ;; boolean (default false)
        required (r/cursor label [:required])
        ;; boolean (default false, available if :required is true)
        consensus (r/cursor label [:consensus])
        ;; required, string
        question (r/cursor label [:question])
        ;;
        definition (r/cursor label [:definition])
;;; type=(boolean or categorical)
        ;; boolean, activates interface for defining inclusion-values
        inclusion (r/cursor label [:inclusion])
;;; type=(boolean or categorical)
        ;; optional, vector of (boolean or string)
        ;; for categorical, the values here must also be in `:all-values`
        inclusion-values (r/cursor definition [:inclusion-values])
;;; type=(categorical or string)
        ;; required, boolean
        multi? (r/cursor definition [:multi?])
;;; type=categorical
        ;; required, vector of strings
        all-values (r/cursor definition [:all-values])
;;; type=string
        ;; optional, vector of strings
        examples (r/cursor definition [:examples])
        ;; required, integer
        max-length (r/cursor definition [:max-length])
;;;
        errors (r/cursor label [:errors])
        make-args (fn [setting extra]
                    (label-setting-field-args setting @errors extra))]
    [:form.ui.form.define-label {:on-submit (util/wrap-user-event
                                             #(if (not (labels-synced?))
                                                ;; save on server
                                                (sync-to-server)
                                                ;; just reset editing
                                                (reset! (r/cursor label [:editing?]) false))
                                             :prevent-default true)}
     (when (string? @value-type)
       [:h5.ui.dividing.header.value-type
        (str (str/capitalize @value-type) " Label")])
     ;; short-label
     [ui/TextInputField
      (make-args :short-label
                 {:value short-label
                  :on-change #(reset! short-label (-> % .-target .-value))})]
     ;; question
     [ui/TextInputField
      (make-args :question
                 {:value question
                  :on-change #(reset! question (-> % .-target .-value))})]
     ;; max-length on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        (make-args :max-length
                   {:value max-length
                    :on-change #(let [value (-> % .-target .-value)]
                                  (reset! max-length (or (sutil/parse-integer value) value)))})])
     ;; examples on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        (make-args :examples
                   {:default-value (str/join "," @examples)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! examples nil)
                                    (reset! examples (str/split value #","))))})])
     (when (= @value-type "categorical")
       ;; FIX: whitespace not trimmed from input strings;
       ;; need to run db migration to fix all existing values
       [ui/TextInputField
        (make-args :all-values
                   {:default-value (str/join "," @all-values)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! all-values nil)
                                    (reset! all-values (str/split value #","))))})])
     ;; required
     [ui/LabeledCheckboxField
      (make-args :required
                 {:checked? @required
                  :on-change #(let [value (-> % .-target .-checked boolean)]
                                (reset! required value)
                                (when (false? value)
                                  (reset! consensus false)))})]
     ;; consensus
     [ui/LabeledCheckboxField
      (make-args :consensus
                 {:checked? @consensus
                  :on-change #(reset! consensus (-> % .-target .-checked boolean))})]
     ;; multi?
     (when (= @value-type "string")
       [ui/LabeledCheckboxField
        (make-args :multi?
                   {:checked? @multi?
                    :on-change #(reset! multi? (-> % .-target .-checked boolean))})])
     ;; inclusion checkbox
     (when (in? ["boolean" "categorical"] @value-type)
       [ui/LabeledCheckboxField
        (make-args :inclusion
                   {:checked? @inclusion
                    :on-change #(let [value (-> % .-target .-checked boolean)]
                                  (reset! inclusion value)
                                  (when (false? value)
                                    (reset! inclusion-values [])))})])
     ;; inclusion-values for categorical label
     (when (and (= @value-type "categorical")
                (not (false? @inclusion))
                (seq @all-values))
       (let [error (get-in @errors [:definition :inclusion-values])]
         [:div.field.inclusion-values {:class (when error "error")
                                       :style {:width "100%"}}
          (FormLabelWithTooltip
           "Inclusion values"
           ["Answers containing any of these values will indicate article inclusion."
            "Non-empty answers otherwise will indicate exclusion."])
          (doall (for [option-value @all-values]
                   ^{:key (gensym option-value)}
                   [ui/LabeledCheckbox
                    {:checked? (contains? (set @inclusion-values) option-value)
                     :on-change
                     #(reset! inclusion-values
                              (if (-> % .-target .-checked)
                                (into [] (conj @inclusion-values option-value))
                                (into [] (remove (partial = option-value) @inclusion-values))))
                     :label option-value}]))
          [show-error-msg error]]))
     ;; inclusion-values for boolean label
     (when (and (= @value-type "boolean")
                (not (false? @inclusion)))
       (let [error (get-in @errors [:definition :inclusion-values])]
         [:div.field.inclusion-values {:class (when error "error")
                                       :style {:width "100%"}}
          (FormLabelWithTooltip
           "Inclusion value"
           ["Select which value should indicate article inclusion."])
          [ui/LabeledCheckbox
           {:checked? (contains? (set @inclusion-values) false)
            :on-change #(let [checked? (-> % .-target .-checked)]
                          (reset! inclusion-values (if checked? [false] [])))
            :label "No"}]
          [ui/LabeledCheckbox
           {:checked? (contains? (set @inclusion-values) true)
            :on-change #(let [checked? (-> % .-target .-checked)]
                          (reset! inclusion-values (if checked? [true] [])))
            :label "Yes"}]
          [show-error-msg error]]))
     [:div.field {:style {:margin-bottom "0.75em"}}
      [:div.ui.two.column.grid {:style {:margin "-0.5em"}}
       [:div.column {:style {:padding "0.5em"}} [SaveLabelButton label]]
       [:div.column {:style {:padding "0.5em"}} [CancelDiscardButton label]]]]
     [:div.field [DisableEnableLabelButton label]]]))

;; this corresponds to
;; (defmethod sysrev.views.review/label-input-el "string" ...)
(defn StringLabelForm [{:keys [set-answer! value label-id definition]}]
  (let [{:keys [multi?]} definition
        left-action? true
        right-action? (if multi? true false)]
    ;; ^{:key [label-id]}
    [:div.ui.form.string-label {:key label-id}
     [:div.field.string-label
      [:div.ui.fluid.labeled.input
       {:class (css [(and left-action? right-action?) "labeled right action"
                     left-action?                     "left action"])}
       (when left-action?
         [:div.ui.label.icon.button.input-remove
          {:class (css [(or (empty? @value) (every? empty? @value)) "disabled"])
           :on-click (util/wrap-user-event #(reset! value [""]))}
          [:i.times.icon]])
       [:input {:type "text"
                :name (str label-id)
                :value (first @value)
                :on-change set-answer!}]
       (when right-action?
         [:a.ui.icon.button.input-row {:class "disabled"} [:i.plus.icon]])]]]))

;; this corresponds to
;; (defmethod sysrev.views.review/label-input-el "categorical" ...)
(defn CategoricalLabelForm [{:keys [definition label-id required value onAdd onRemove]}]
  (r/create-class
   {:component-did-mount
    (fn [c]
      (-> (js/$ (r/dom-node c))
          (.dropdown (clj->js {:onAdd onAdd
                               :onRemove onRemove
                               :onChange (fn [_] (-> (js/$ (r/dom-node c))
                                                     (.dropdown "hide")))}))))
    :reagent-render
    (fn [{:keys [definition label-id required value onAdd onRemove]}]
      (let [{:keys [all-values]} definition
            special-value? #(in? ["none" "other"] (str/lower-case %))
            values (if (every? string? all-values)
                     (concat
                      (->> all-values (filter special-value?))
                      (->> all-values (remove special-value?)
                           (sort #(compare (str/lower-case %1) (str/lower-case %2)))))
                     all-values)
            dom-id (str "label-edit-" label-id)
            search? (or (and (util/desktop-size?) (>= (count values) 25))
                        (>= (count values) 40))]
        [:div.ui.fluid.multiple.selection
         {:id dom-id
          :class (css [search? "search"] "dropdown")
          ;; hide dropdown on click anywhere in main dropdown box
          :on-click (util/wrap-user-event
                     #(let [target (-> % .-target)]
                        (when (or (= dom-id (.-id target))
                                  (-> (js/$ target) (.hasClass "default"))
                                  (-> (js/$ target) (.hasClass "label")))
                          (let [dd (js/$ (str "#" dom-id))]
                            (when (.dropdown dd "is visible")
                              (.dropdown dd "hide"))))))}
         [:input {:type "hidden"
                  :name (str "label-edit(" dom-id ")")
                  :value (str/join "," @value)}]
         [:i.dropdown.icon]
         [:div.default.text "No answer selected"
          (when required [:span.default.bold " (required)"])]
         [:div.menu (doall (map-indexed (fn [i lval] ^{:key i}
                                          [:div.item {:data-value (str lval)} (str lval)])
                                        values))]]))}))

;; this corresponds to sysrev.views.review/label-column
(defn Label [label]
  (let [{:keys [label-id value-type question short-label #_ category
                required definition]} @label
        answer (r/cursor label [:answer])
        on-click-help (util/wrap-user-event #(do nil))]
    [:div.ui.column.label-edit {:class (css [required "required"])}
     [:div.ui.middle.aligned.grid.label-edit
      [ui/with-tooltip
       (let [name-content [:span.name {:class (css [(>= (count short-label) 30) "small-text"])}
                           [:span.inner (str short-label)]]]
         (if (and (util/mobile?) (>= (count short-label) 30))
           [:div.ui.row.label-edit-name {:on-click on-click-help}
            [InclusionTag @label]
            [:span.name " "]
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])
            [:div.clear name-content]]
           [:div.ui.row.label-edit-name {:on-click on-click-help}
            [InclusionTag @label] name-content
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])]))
       {:variation "basic"
        :delay {:show 650, :hide 0}
        :hoverable false
        :inline true
        :position "top center"
        :distanceAway 8}]
      [label-help-popup @label]
      [:div.ui.row.label-edit-value {:class (case value-type
                                              "boolean"      "boolean"
                                              "categorical"  "category"
                                              "string"       "string"
                                              nil)}
       [:div.inner
        (case value-type
          "boolean"      [ui/three-state-selection
                          {:value answer
                           :set-answer! #(reset! answer %)}]
          "string"       [StringLabelForm
                          {:value answer
                           :set-answer! #(reset! answer [(-> % .-target .-value)])
                           :label-id label-id
                           :definition definition}]
          "categorical"  [CategoricalLabelForm
                          {:value answer
                           :definition definition
                           :label-id label-id
                           :onAdd (fn [v _t] (swap! answer conj v))
                           :onRemove
                           (fn [v _t] (swap! answer #(into [] (remove (partial = v) %))))}]
          (pr-str label))]]]]))

(defn- LabelItem [i label & {:keys [status]}]
  (let [{:keys [label-id name editing? enabled]} @label
        admin? (or @(subscribe [:member/admin?])
                   @(subscribe [:user/admin?]))
        allow-edit? (and admin? (not= name "overall include"))
        {:keys [draggable]} status]
    [:div.ui.middle.aligned.grid.label-item {:id (str label-id)
                                             :class (css [(not enabled) "secondary"] "segment")}
     [:div.row
      [ui/TopAlignedColumn
       [:div.ui.label {:class (css [enabled "blue" :else "gray"])} (str (inc i))]
       (css "two wide center aligned column label-index"
            [(true? draggable)  "cursor-grab"
             (false? draggable) "cursor-not-allowed"])]
      [:div.column.define-label-item {:class "twelve wide"}
       (if editing? [LabelEditForm label] [Label label])]
      [ui/TopAlignedColumn
       [EditLabelButton label allow-edit?]
       "two wide center aligned column delete-label"]]]))

(def label-drag-spec
  (dnd/make-drag-spec
   {:begin-drag (fn [props _monitor _component]
                  #_ (util/log "begin-drag called: props = %s" (pr-str props))
                  {:label-id (-> props :id-spec :label-id)})}))

(def label-drop-spec
  (dnd/make-drop-spec
   {:drop (fn [props monitor _component]
            #_ (util/log "drop called: props = %s" (pr-str props))
            ;; Save changes to server when drag interaction is finished
            (sync-to-server)
            {:label-id (-> props :id-spec :label-id)
             :item (.getItem monitor)})}))

(defn wrap-label-dnd [id-spec content-fn]
  [dnd/wrap-dnd
   id-spec
   {:item-type    "label-edit"
    :drag-spec    label-drag-spec
    :drop-spec    label-drop-spec
    :content      (fn [props]
                    #_ (when (:is-dragging props)
                         (util/log "wrap-label-dnd: props = %s" (pr-str props)))
                    (content-fn props))
    :on-enter    (fn [props]
                   (let [src-id (-> props :item :label-id)
                         dest-id (-> props :id-spec :label-id)]
                     (when (not= src-id dest-id)
                       #_ (util/log "on-enter: dragging %s to %s" src-id dest-id)
                       (move-label-to src-id dest-id))))
    :on-exit     (fn [_props]
                   #_ (util/log "on-exit: props = %s" (pr-str props))
                   nil)}])

(defmethod panel-content panel []
  (fn [_child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
          labels (r/cursor state [:labels])
          read-only-message-closed? (r/cursor state [:read-only-message-closed?])
          sort-label-ids (fn [enabled?]
                           (->> (sort-client-project-labels @labels (not enabled?))
                                (filter #(= enabled? (:enabled (get @labels %))))))
          active-ids (sort-label-ids true)
          disabled-ids (sort-label-ids false)]
      (ensure-state)
      [:div.define-labels
       [ReadOnlyMessage
        "Editing label definitions is restricted to project administrators."
        read-only-message-closed?]
       [:div.ui.two.column.stackable.grid.label-items
        (doall (map-indexed
                (fn [i label-id]
                  (if (= (get-in @labels [label-id :name]) "overall include")
                    ;; "overall include" should not be movable, always first
                    ^{:key [:label-item i label-id]}
                    [:div.column {:key [:label-column i label-id]}
                     [LabelItem i (r/cursor state [:labels label-id])
                      :status {:draggable false}]]
                    ;; other active labels should be movable
                    ^{:key [:label-item i label-id]}
                    [wrap-label-dnd {:idx i, :label-id (str label-id)}
                     (fn [{:as _props}]
                       [:div.column {:key [:label-column i label-id]}
                        [LabelItem i (r/cursor state [:labels label-id])
                         :status {:draggable true}]])]))
                active-ids))]
       (when (seq disabled-ids)
         [:h4.ui.block.header "Disabled Labels"])
       ;; TODO: collapse disabled labels by default, display on click
       (when (seq disabled-ids)
         [:div.ui.two.column.stackable.grid.label-items
          (doall (map-indexed
                  (fn [i label-id] ^{:key [:label-id label-id]}
                    [:div.column [LabelItem i (r/cursor state [:labels label-id])]])
                  disabled-ids))])
       (when admin?
         [:div.ui.three.column.stackable.grid
          [:div.column [AddLabelButton "boolean"]]
          [:div.column [AddLabelButton "categorical"]]
          [:div.column [AddLabelButton "string"]]])])))

(defmethod panel-content [:project :project :labels] []
  (fn [child]
    [:div.project-content
     (when-let [project-id @(subscribe [:active-project-id])]
       (when @(subscribe [:have? [:project project-id]])
         child))]))
