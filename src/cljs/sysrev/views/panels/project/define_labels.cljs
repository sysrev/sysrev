(ns sysrev.views.panels.project.define-labels
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-sub-raw
              reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.labels :as labels]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components :as ui]
            [sysrev.views.review :refer [label-help-popup inclusion-tag]]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.util :as u]
            [sysrev.shared.util :as su :refer [in? map-values]]))

;; Convention - A (new) label that exists in the client but not on the
;; server has a label-id of type string
;;
;; After it is saved on the server, the label-id type is
;; #object[Transit$UUID] on the client and java.util.UUID on the
;; server

;; The jQuery plugin formBuilder is inspiration for the UI
;; repo: https://github.com/kevinchappell/formBuilder
;; demo: https://jsfiddle.net/kevinchappell/ajp60dzk/5/

(def panel [:project :project :labels :edit])

(def initial-state {:read-only-message-closed? false})

(defonce state (r/cursor app-db [:state :panels panel]))

(defn- saved-labels
  "Get the label values for project from the app-db"
  []
  (let [db @app-db]
    (get-in db [:data :project (active-project-id db) :labels])))

(defn to-local-labels
  "Convert labels map to format used by local namespace state."
  [labels]
  (let [insert-answer #(condp = (:value-type %)
                         "boolean" (assoc % :answer nil)
                         (assoc % :answer []))
        add-local-keys #(assoc % :editing? (not-empty (:errors %)))
        set-inclusion (fn [label]
                        (if (contains? label :inclusion)
                          label
                          (let [ivalues (-> label :definition :inclusion-values)]
                            (assoc label :inclusion (not (empty? ivalues))))))]
    (map-values (comp insert-answer add-local-keys set-inclusion) labels)))

(defn set-local-labels!
  "Sets value of local labels state from labels map, applying some
  conversions to format correctly for local state."
  [labels]
  (swap! state assoc-in [:labels] (to-local-labels labels)))

(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state))
  (let [labels (r/cursor state [:labels])]
    (when (empty? @labels)
      (set-local-labels! (saved-labels)))))

(reg-event-fx
 :define-labels/reset-state!
 [trim-v]
 (fn [_]
   (reset! state initial-state)))

(defn- get-local-labels []
  (get-in @state [:labels]))

(defn max-project-ordering
  "Obtain the max-project-ordering from the local state of labels"
  []
  (apply max (map :project-ordering (vals (get-local-labels)))))

(defn default-answer
  "Return a default answer for value-type"
  [value-type]
  (condp = value-type
    "boolean" nil
    "string" []
    "categorical" []
    ;; default
    nil))

(defn create-blank-label
  "Create a label map"
  [value-type]
  (let [label-id (str "new-label-" (su/random-id))]
    {:definition
     (cond-> {}
       (= value-type "boolean") (assoc :inclusion-values [])
       (= value-type "string") (assoc :multi? false)
       (= value-type "categorical") (assoc :inclusion-values []
                                           :multi? true)),
     :inclusion false
     :category "extra"
     :name (str value-type (su/random-id))
     :project-ordering (inc (max-project-ordering))
     :label-id label-id ;; this is a string, to distinguish unsaved labels
     :project-id (active-project-id @app-db)
     :enabled true
     :value-type value-type
     :required false

     ;; these last fields are used only internally by this namespace;
     ;; filtered before exporting elsewhere
     :answer (default-answer value-type)
     :editing? true
     :errors (list)}))

(defn- set-labels [labels]
  (swap! state assoc-in [:labels] labels))

(defn add-new-label!
  "Add a new label to the local state"
  [label]
  (swap! state assoc-in [:labels (:label-id label)] label))

(defn errors-in-labels?
  "Are there errors in the labels?"
  [labels]
  (not (every? nil? (map :errors (vals labels)))))

(defn to-global-labels
  "Convert labels map to format used by global client state and server."
  [labels]
  (->> labels
       vals
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
  (= (saved-labels)
     (to-global-labels (get-local-labels))))

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
  (reset!
   (r/cursor app-db [:data :project (active-project-id @app-db) :labels])
   labels))

(defn reset-local-label! [label-id]
  "Resets local state of label to saved state, or discards unsaved."
  (if (string? label-id)
    (swap! (r/cursor state [:labels]) dissoc label-id)
    (reset! (r/cursor state [:labels label-id])
            (-> (saved-labels) to-local-labels (get label-id)))))

(defn DisableEnableLabelButton
  [label]
  (let [labels (r/cursor state [:labels])
        label-id (r/cursor label [:label-id])
        enabled? (r/cursor label [:enabled])
        [text icon-class] (if @enabled?
                            ["Disable Label" "circle minus"]
                            ["Enable Label" "circle plus"])]
    ;; don't show this button for unsaved labels
    (when-not (string? @label-id)
      [:button.ui.small.fluid.labeled.icon.button
       {:class (if @enabled? "" "primary")
        :on-click (u/wrap-user-event #(do (reset-local-label! @label-id)
                                          (swap! (r/cursor label [:enabled]) not)
                                          (sync-to-server))
                                     :prevent-default true)}
       [:i {:class (str icon-class " icon")}]
       text])))

(defn CancelDiscardButton
  [label]
  (let [labels (r/cursor state [:labels])
        label-id (r/cursor label [:label-id])
        enabled? (r/cursor label [:enabled])
        text (if (string? @label-id) "Discard" "Cancel")]
    [:button.ui.small.fluid.labeled.icon.button
     {:on-click (u/wrap-user-event #(reset-local-label! @label-id)
                                   :prevent-default true)}
     [:i.circle.times.icon]
     text]))

(defn save-request-active? []
  (loading/any-action-running? :only :labels/sync-project-labels))

(defn SaveLabelButton
  [label]
  [:button.ui.small.fluid.positive.labeled.icon.button
   {:type "submit"
    :class (cond-> ""
             (save-request-active?) (str " loading")
             (labels-synced?)       (str " disabled"))}
   [:i.check.circle.outline.icon]
   "Save"])

(defn EditLabelButton
  "label is a cursor into the state representing the label"
  [label allow-edit?]
  (let [{:keys [editing?]} @label
        synced? (labels-synced?)]
    [:div.ui.small.icon.button.edit-label-button
     {:class (when-not allow-edit? "disabled")
      :style {:margin-left 0 :margin-right 0}
      :on-click (u/wrap-user-event #(do (when editing? (sync-to-server))
                                        (swap! (r/cursor label [:editing?]) not)))}
     (if editing?
       (if synced?
         [:i.circle.check.icon]
         [:i.green.circle.check.icon])
       [:i.edit.icon])]))

(defn- AddLabelButton
  "Add a label of type"
  [value-type]
  [:button.ui.fluid.large.labeled.icon.button
   {:on-click (u/wrap-user-event #(add-new-label! (create-blank-label value-type)))}
   [:i.plus.circle.icon]
   (str "Add " (str/capitalize value-type) " Label")])

(def-action :labels/sync-project-labels
  :uri (fn [] "/api/sync-project-labels")
  ;; labels in this case have already been processed for the server
  :content (fn [project-id labels]
             {:project-id project-id, :labels labels})
  :process (fn [_ [project-id _] {:keys [valid? labels] :as result}]
             (if valid?
               ;; no errors
               (do
                 ;; update client project data with new labels
                 (set-app-db-labels! labels)
                 ;; set local state to new labels
                 (set-local-labels! labels))
               ;; set local state to new labels (includes error messages)
               (set-local-labels! labels))
             ;; an empty handler
             {}))

(defn InclusionTag
  [{:keys [category answer definition value-type]}]
  (let [criteria? (= category "inclusion criteria")
        inclusion-values (:inclusion-values definition)
        inclusion (case value-type
                    "boolean"
                    (cond
                      (empty? inclusion-values) nil
                      (nil? answer) nil
                      :else (boolean (in? inclusion-values answer)))
                    "categorical"
                    (cond
                      (empty? inclusion-values) nil
                      (nil? answer) nil
                      (empty? answer) nil
                      :else (boolean (some (in? inclusion-values) answer)))
                    nil)
        [color iclass]
        (cond (not criteria?)
              ["grey" "content"]

              (true? inclusion)
              ["green" "circle plus"]

              (false? inclusion)
              ["orange" "circle minus"]

              :else
              ["grey" "circle outline"])]
    [:i.left.floated.fitted {:class (str color " " iclass " icon")}]))

(defn FormLabelWithTooltip [text tooltip-content]
  (doall (ui/with-ui-help-tooltip
           [:label text " " [ui/ui-help-icon]]
           :help-content tooltip-content
           :popup-options {:delay {:show 750 :hide 0}})))

(defn LabelEditForm [label]
  (let [label-id (r/cursor label [:label-id])
        on-server? (not (string? @label-id))
        show-error-msg (fn [msg] (when msg [:div.ui.red.message msg]))
        value-type (r/cursor label [:value-type])
        answer (r/cursor label [:answer])

;;; all types
        ;; required, string (redundant with label-id)
        name (r/cursor label [:name])
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

        errors (r/cursor label [:errors])]
    [:form.ui.form.define-label
     {:on-submit (u/wrap-user-event
                  #(if (not (labels-synced?))
                     ;; save on server
                     (sync-to-server)
                     ;; just reset editing
                     (reset! (r/cursor label [:editing?]) false))
                  :prevent-default true)}

     ;; show this only for saved labels
     (when (string? @value-type)
       [:h5.ui.dividing.header.value-type
        (str (str/capitalize @value-type) " Label")])

     ;; short-label
     [ui/TextInputField
      {:field-class "label-name"
       :error (:short-label @errors)
       :value short-label
       :on-change #(reset! short-label (-> % .-target .-value))
       :label "Name"}]

     ;; question
     [ui/TextInputField
      {:field-class "label-question"
       :error (:question @errors)
       :value question
       :on-change #(reset! question (-> % .-target .-value))
       :label "Question"
       :tooltip ["Describe the meaning of this label for reviewers."
                 "Displayed as tooltip in review interface."]}]

     ;; max-length on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        {:field-class "max-length"
         :error (get-in @errors [:definition :max-length])
         :value max-length
         :on-change #(let [value (-> % .-target .-value)]
                       (reset! max-length (or (su/parse-integer value) value)))
         :placeholder "100"
         :label "Max length"}])

     ;; examples on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        {:field-class "examples"
         :error (get-in @errors [:definition :examples])
         :default-value (str/join "," @examples)
         :type "text"
         :placeholder "example one,example two"
         :on-change #(let [value (-> % .-target .-value)]
                       (if (empty? value)
                         (reset! examples nil)
                         (reset! examples (str/split value #","))))
         :label "Examples (comma-separated)"
         :tooltip ["Examples of possible label values for reviewers."
                   "Displayed as tooltip in review interface."]}])

     (when (= @value-type "categorical")
       ;; FIX: whitespace not trimmed from input strings;
       ;; need to run db migration to fix all existing values
       [ui/TextInputField
        {:field-class "categories"
         :error (get-in @errors [:definition :all-values])
         :default-value (str/join "," @all-values)
         :placeholder "one,two,three"
         :on-change #(let [value (-> % .-target .-value)]
                       (if (empty? value)
                         (reset! all-values nil)
                         (reset! all-values (str/split value #","))))
         :label "Categories (comma-separated options)"
         :tooltip ["List of values allowed for label."
                   "Reviewers may select multiple values in their answers."]}])

     ;; required
     [ui/LabeledCheckboxField
      {:field-class "require-answer"
       :error (:required @errors)
       :on-change #(let [value (-> % .-target .-checked boolean)]
                     (reset! required value)
                     (when (false? value)
                       (reset! consensus false)))
       :checked? @required
       :label "Require answer"
       :tooltip ["Require users to provide an answer for this label before saving article."]}]

     ;; consensus
     [ui/LabeledCheckboxField
      {:field-class "consensus"
       :error (:consensus @errors)
       :on-change #(reset! consensus (-> % .-target .-checked boolean))
       :checked? @consensus
       ;; :disabled? (false? @required)
       :label "Require user consensus"
       :tooltip ["Check answers for consensus among users."
                 "Articles will be marked as conflicted if user answers are not identical."]}]

     ;; multi?
     (when (= @value-type "string")
       [ui/LabeledCheckboxField
        {:field-class "allow-multiple"
         :error (get-in @errors [:definition :multi?])
         :on-change #(reset! multi? (-> % .-target .-checked boolean))
         :checked? @multi?
         :label "Allow multiple values"
         :tooltip ["Allow answers to contain multiple string values."]}])

     (when (in? ["boolean" "categorical"] @value-type)
       [ui/LabeledCheckboxField
        {:field-class "inclusion-criteria"
         :error (:required @errors)
         :on-change #(let [value (-> % .-target .-checked boolean)]
                       (reset! inclusion value)
                       (when (false? value)
                         (reset! inclusion-values [])))
         :checked? @inclusion
         :label "Inclusion criteria"
         :tooltip ["Define a relationship between this label and article inclusion."
                   "Users will be warned if their answers contradict the value selected for article inclusion."]}])

     ;; inclusion-values on a categorical label
     (when (and (= @value-type "categorical")
                (not (false? @inclusion))
                (not (empty? @all-values)))
       [:div.field.inclusion-values
        {:class (when (get-in @errors [:definition :all-values])
                  "error")
         :style {:width "100%"}}
        (FormLabelWithTooltip
         "Inclusion values"
         ["Answers containing any of these values will indicate article inclusion."
          "Non-empty answers otherwise will indicate exclusion."])
        (doall
         (map
          (fn [option-value]
            ^{:key (gensym option-value)}
            [ui/LabeledCheckbox
             {:checked? (contains? (set @inclusion-values) option-value)
              :on-change
              (fn [event]
                (let [checked? (-> event .-target .-checked)]
                  (reset! inclusion-values
                          (if checked?
                            (into [] (conj (set @inclusion-values)
                                           option-value))
                            (into [] (remove #(= option-value %)
                                             (set @inclusion-values)))))))
              :label option-value}])
          @all-values))
        [show-error-msg (get-in @errors [:definition :inclusion-values])]])

     ;; inclusion-values on a boolean label
     (when (and (= @value-type "boolean")
                (not (false? @inclusion)))
       [:div.field.inclusion-values
        {:class (when (get-in @errors [:definition :inclusion-values])
                  "error")}
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
        [show-error-msg (get-in @errors [:definition :inclusion-values])]])
     [:div.field {:style {:margin-bottom "0.75em"}}
      [:div.ui.two.column.grid {:style {:margin "-0.5em"}}
       [:div.column {:style {:padding "0.5em"}}
        [SaveLabelButton label]]
       [:div.column {:style {:padding "0.5em"}}
        [CancelDiscardButton label]]]]
     [:div.field
      [DisableEnableLabelButton label]]]))

;; this corresponds to
;; (defmethod sysrev.views.review/label-input-el "string" ...)
(defn StringLabelForm
  [{:keys [set-answer! value label-id definition]}]
  (let [left-action? true
        {:keys [multi?]} definition
        right-action? (if multi? true false)]
    ^{:key [label-id]}
    [:div.ui.form.string-label
     [:div.field.string-label
      {:class "" }
      [:div.ui.fluid.labeled.input
       {:class (cond-> ""
                 (and left-action? right-action?)
                 (str " labeled right action")
                 (and left-action? (not right-action?))
                 (str " left action"))}
       (when left-action?
         [:div.ui.label.icon.button.input-remove
          {:class (cond-> ""
                    (or (empty? @value)
                        (every? empty? @value)) (str " disabled"))
           :on-click (u/wrap-user-event #(reset! value [""]))}
          [:i.times.icon]])
       [:input
        {:type "text"
         :name (str label-id)
         :value (first @value)
         :on-change set-answer!}]
       (when right-action?
         [:a.ui.icon.button.input-row
          {:class "disabled"}
          [:i.plus.icon]])]]]))

;; this corresponds to
;; (defmethod sysrev.views.review/label-input-el "categorical" ...)
(defn CategoricalLabelForm
  [{:keys [definition label-id required value
           onAdd onRemove]}]
  (let [all-values (:all-values definition)]
    (r/create-class
     {:component-did-mount
      (fn [c]
        (-> (js/$ (r/dom-node c))
            (.dropdown
             (clj->js
              {:onAdd onAdd
               :onRemove onRemove
               :onChange
               (fn [_] (.dropdown (js/$ (r/dom-node c))
                                  "hide"))}))))
      :reagent-render
      (fn [{:keys [definition label-id required value
                   onAdd onRemove]}]
        (let [required? required
              all-values
              (as-> all-values vs
                (if (every? string? vs)
                  (concat
                   (->> vs (filter #(in? ["none" "other"] (str/lower-case %))))
                   (->> vs (remove #(in? ["none" "other"] (str/lower-case %)))
                        (sort #(compare (str/lower-case %1)
                                        (str/lower-case %2)))))
                  vs))
              dom-id (str "label-edit-" label-id)
              dropdown-class (if (or (and (>= (count all-values) 25)
                                          (u/desktop-size?))
                                     (>= (count all-values) 40))
                               "search dropdown" "dropdown")]
          [:div.ui.fluid.multiple.selection
           {:id dom-id
            :class dropdown-class
            ;; hide dropdown on click anywhere in main dropdown box
            :on-click (u/wrap-user-event
                       #(let [target (-> % .-target)]
                          (when (or (= dom-id (.-id target))
                                    (-> (js/$ target) (.hasClass "default"))
                                    (-> (js/$ target) (.hasClass "label")))
                            (let [dd (js/$ (str "#" dom-id))]
                              (when (.dropdown dd "is visible")
                                (.dropdown dd "hide")))))
                       :timeout false)}
           [:input
            {:name (str "label-edit(" dom-id ")")
             :value (str/join "," @value)
             :type "hidden"}]
           [:i.dropdown.icon]
           (if required?
             [:div.default.text
              "No answer selected "
              [:span.default {:style {:font-weight "bold"}}
               "(required)"]]
             [:div.default.text "No answer selected"])
           [:div.menu
            (doall
             (->>
              all-values
              (map-indexed
               (fn [i lval]
                 ^{:key [i]}
                 [:div.item {:data-value (str lval)}
                  (str lval)]))))]]))})))

;; this corresponds to sysrev.views.review/label-column
(defn Label
  [label]
  (let [{:keys [value-type question short-label
                label-id category required]} @label
        on-click-help (u/wrap-user-event #(do nil) :timeout false)]
    [:div.ui.column.label-edit {:class (when required "required")}
     [:div.ui.middle.aligned.grid.label-edit
      [ui/with-tooltip
       (let [name-content
             [:span.name
              {:class (when (>= (count short-label) 30)
                        "small-text")}
              [:span.inner (str short-label)]]]
         (if (and (u/mobile?) (>= (count short-label) 30))
           [:div.ui.row.label-edit-name
            {:on-click on-click-help}
            [InclusionTag @label]
            [:span.name " "]
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])
            [:div.clear name-content]]
           [:div.ui.row.label-edit-name
            {:on-click on-click-help}
            [InclusionTag @label]
            name-content
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])]))
       {:variation "basic"
        :delay {:show 400, :hide 0}
        :hoverable false
        :inline true
        :position "top center"
        :distanceAway 8}]
      [label-help-popup @label]
      [:div.ui.row.label-edit-value
       {:class (case value-type
                 "boolean"      "boolean"
                 "categorical"  "category"
                 "string"       "string"
                 "")}
       [:div.inner
        (let [value (r/cursor label [:answer])
              {:keys [label-id definition required]} @label]
          (condp = value-type
            ;; this is the only form that is shared with
            ;; sysrev.views.review
            "boolean"
            [ui/three-state-selection {:set-answer! #(reset! value %)
                                       :value value}]
            "string"
            [StringLabelForm {:value value
                              :set-answer!
                              #(reset! value [(-> % .-target .-value)])
                              :label-id label-id
                              :definition definition}]
            "categorical"
            [CategoricalLabelForm
             {:value value
              :definition definition
              :label-id label-id
              :onAdd (fn [v t]
                       (swap! value conj v))
              :onRemove (fn [v t]
                          (swap! value
                                 #(into [] (remove (partial = v) %))))}]
            (pr-str label)))]]]]))

(defn- LabelItem [i label]
  "label is a cursor"
  (let [editing? (r/cursor label [:editing?])
        errors (r/cursor label [:errors])
        name (r/cursor label [:name])
        label-id (r/cursor label [:label-id])
        admin? (or @(subscribe [:member/admin?])
                   @(subscribe [:user/admin?]))
        enabled? @(r/cursor label [:enabled])
        [side-width center-width] (if (u/mobile?)
                                    ["two wide" "twelve wide"]
                                    ["two wide" "twelve wide"])]
    [:div.ui.middle.aligned.grid.segment.label-item
     {:class (if enabled? nil "secondary")
      :id (str @label-id)}
     [:div.row
      [ui/TopAlignedColumn
       [:div.ui.label {:class (if enabled? "blue" "gray")}
        (str (inc i))]
       (str side-width " center aligned column label-index")]
      [:div.column.define-label-item
       {:class center-width}
       (if @editing?
         [LabelEditForm label]
         [Label label])]
      [ui/TopAlignedColumn
       (let [allow-edit? (and (not= @name "overall include")
                              admin?)]
         [EditLabelButton label allow-edit?])
       (str side-width " center aligned column delete-label")]]]))

(defmethod panel-content panel []
  (fn [child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
          labels (r/cursor state [:labels])
          read-only-message-closed? (r/cursor state [:read-only-message-closed?])
          sort-label-ids
          (fn [enabled?]
            (->> (concat
                  (->> (saved-labels)
                       (apply concat)
                       (apply hash-map)
                       (#(labels/sort-project-labels % true)))
                  (->> @labels
                       (remove
                        (fn [[label-id label]]
                          (uuid? label-id)))
                       (sort-by
                        (fn [[label-id label]]
                          (:project-ordering label)))
                       (mapv first)))
                 (filter (fn [label-id]
                           (let [label (get @labels label-id)]
                             (cond (true? enabled?)
                                   (true? (:enabled label))

                                   (false? enabled?)
                                   (false? (:enabled label))

                                   :else true))))))
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
                  ^{:key [:label-item label-id]}
                  ;; let's pass a cursor to the state
                  [:div.column [LabelItem i (r/cursor state [:labels label-id])]])
                active-ids))]
       (when (not-empty disabled-ids)
         [:h4.ui.block.header "Disabled Labels"])
       ;; FIX: collapse disabled labels by default, display on click
       (when (not-empty disabled-ids)
         [:div.ui.two.column.stackable.grid.label-items
          (doall (map-indexed
                  (fn [i label-id]
                    ^{:key [:label-id label-id]}
                    ;; let's pass a cursor to the state
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
     child]))
