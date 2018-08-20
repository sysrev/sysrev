(ns sysrev.views.panels.project.define-labels
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-sub-raw
              reg-event-db reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.labels :as labels]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components :as ui]
            [sysrev.views.review :refer [label-help-popup inclusion-tag]]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in?]]))

;; Convention -
;; A (new) label that exists in the client but not on the server
;; has a label-id of type string
;;
;; After it is saved on the server, the label-id type is #object[Transit$UUID] on the client and java.util.UUID on the server

;; The jQuery plugin formBuilder is inspiration for the UI
;; repo: https://github.com/kevinchappell/formBuilder
;; demo: https://jsfiddle.net/kevinchappell/ajp60dzk/5/

(def panel [:project :project :labels :edit])

(def initial-state {:server-syncing? false
                    :read-only-message-closed? false})

(defonce state (r/cursor app-db [:state :panels panel]))

(defn- saved-labels
  "Get the label values for project from the app-db"
  []
  (let [db @app-db]
    (get-in db [:data :project (active-project-id db) :labels])))

(defn map-labels
  "Apply f to each label in labels"
  [f labels]
  (->> labels vals
       (map f)
       (map #(hash-map (:label-id %) %))
       (apply merge)))

(defn labels->local-labels
  "Add default answers to each label"
  [labels]
  (let [insert-answer-fn (fn [label-value]
                           (condp = (:value-type label-value)
                             "boolean"
                             (assoc label-value :answer nil)
                             (assoc label-value :answer [])))
        add-local-keys (fn [label-value]
                         (-> label-value
                             (assoc :editing? false)))]
    (map-labels (comp insert-answer-fn add-local-keys)
                labels)))

(defn sync-local-labels!
  "Sync the local state labels with labels map. Labels should already have the proper local keys added to them via labels->local-labels"
  [labels]
  (swap! state
         assoc-in [:labels]
         labels))

(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state))
  (let [labels (r/cursor state [:labels])]
    (when (empty? @labels)
      (sync-local-labels! (labels->local-labels (saved-labels))))))

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
  (let [label-id (str "new-label-" (util/random-id))]
    {:definition
     (cond-> {}
       (= value-type "boolean") (assoc :inclusion-values [])
       (= value-type "string") (assoc :multi? false)
       ;; TODO: run db migration to fix `multi?` values for categorical
       ;; (should always be true currently)
       (= value-type "categorical") (assoc :inclusion-values []
                                           :multi? true)),
     ;; we are assuming people want all labels as inclusion criteria
     ;; label influences inclusion
     :category "extra"
     :name (str value-type (util/random-id))
     :project-ordering (inc (max-project-ordering))
     ;; string, per convention
     :label-id label-id
     :project-id (active-project-id @app-db)
     :enabled true
     :value-type value-type
     ;; must be answered
     :required false
     ;; below are values for editing labels
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

(defn local-labels->global-labels
  [labels]
  (->> labels
       vals
       ;; TODO: run db migration to fix label `category` values
       (map (fn [{:keys [definition] :as label}]
              (let [{:keys [inclusion-values]} definition]
                ;; set correct `category` value based on `inclusion-values`
                (assoc label :category
                       (if (not-empty inclusion-values)
                         "inclusion criteria" "extra")))))
       (map #(dissoc % :editing? :answer :errors))
       (map #(hash-map (:label-id %) %))
       (apply merge)))

(defn labels-synced?
  "Are the labels synced with the global app-db?"
  []
  (= (saved-labels)
     (local-labels->global-labels (get-local-labels))))

(defn single-label-synced?
  [label-id]
  (= (get (saved-labels) label-id)
     (get (local-labels->global-labels (get-local-labels)) label-id)))

(defn sync-to-server
  "Send local labels to server to update DB."
  []
  (when (not (labels-synced?))
    (dispatch [:action [:labels/sync-project-labels
                        (active-project-id @app-db)
                        (local-labels->global-labels (get-local-labels))]])))

(defn sync-app-db-labels!
  "Sync the local labels with the global labels. label maps in labels
  should not have local keywords such as :editing?, :errors, etc"
  [labels]
  ;; set the global labels
  (reset!
   (r/cursor app-db
             [:data :project (active-project-id @app-db) :labels])
   labels))

(defn DisableEnableLabelButton
  [label]
  (let [labels (r/cursor state [:labels])
        label-id (r/cursor label [:label-id])
        enabled? (r/cursor label [:enabled])
        [text icon-class]
        (cond (string? @label-id)
              ["Discard" "circle times"]
              @enabled?
              ["Disable" "circle minus"]
              (not @enabled?)
              ["Enable" "circle plus"])]
    [:button.ui.fluid.labeled.icon.button
     {:on-click
      (util/wrap-user-event
       #(if (string? @label-id)
          ;; this is just an ephemeral label that hasn't been saved
          (swap! labels dissoc @label-id)
          ;; label already exists on server
          (do
            ;; we need to reset the label back to its initial
            ;; state from the server so that the user can
            ;; just delete a label, even with errors in it
            (reset! label (get (saved-labels) @label-id))
            ;; set the label to disabled
            (swap! (r/cursor label [:enabled]) not)
            ;; save the labels
            (sync-to-server)))
       :prevent-default true)}
     [:i {:class (str icon-class " icon")}]
     text]))

(defn SaveLabelButton
  [label]
  [:button.ui.fluid.positive.labeled.icon.button
   {:type "submit"
    :class (cond-> ""
             @(r/cursor state [:server-syncing?])
             (str " loading")
             (labels-synced?)
             (str " disabled"))}
   [:i.check.circle.outline.icon]
   "Save"])

(defn EditLabelButton
  "label is a cursor into the state representing the label"
  [label allow-edit?]
  (let [{:keys [editing?]} @label
        synced? (labels-synced?)]
    [:div.ui.small.icon.button
     {:class (if allow-edit? "" "disabled")
      :on-click
      (util/wrap-user-event
       #(do (when editing?
              ;; save the labels
              (sync-to-server))
            ;; set the editing? to its boolean opposite
            (swap! (r/cursor label [:editing?]) not)))
      :style {:margin-left 0
              :margin-right 0}}
     (if editing?
       (if synced?
         [:i.circle.check.icon]
         [:i.green.circle.check.icon])
       [:i.edit.icon])]))

(defn- AddLabelButton
  "Add a label of type"
  [value-type]
  [:button.ui.fluid.large.labeled.icon.button
   {:on-click
    (util/wrap-user-event
     #(add-new-label! (create-blank-label value-type)))}
   [:i.plus.circle.icon]
   (str "Add " (str/capitalize value-type) " Label")])

(def-action :labels/sync-project-labels
  :uri (fn [] "/api/sync-project-labels")
  ;; labels in this case have already been processed for the server
  :content (fn [project-id labels]
             (swap! state assoc-in [:server-syncing?] true)
             {:project-id project-id
              :labels labels})
  :process (fn [_ _ {:keys [valid? labels] :as result}]
             (swap! state assoc-in [:server-syncing?] false)
             (if valid?
               ;; no errors
               (do
                 ;; sync the labels with app-db
                 (sync-app-db-labels! labels)
                 ;; sync the local labels with app-db
                 (sync-local-labels! (labels->local-labels labels)))
               ;; errors
               (do
                 ;; sync the local labels, now with errors
                 (sync-local-labels!
                  (map-labels (fn [label]
                                (if (:errors label)
                                  (assoc label :editing? true)
                                  (assoc label :editing? false)))
                              (labels->local-labels labels)))))
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

(defn LabelEditForm [label]
  (let [label-id (r/cursor label [:label-id])
        on-server? (not (string? @label-id))
        error-message-class {:class "ui red message"}
        value-type (r/cursor label [:value-type])
        answer (r/cursor label [:answer])

;;; all types
        ;; required, string (redundant with label-id)
        name (r/cursor label [:name])
        ;; required, string
        short-label (r/cursor label [:short-label])
        ;; required, boolean
        required (r/cursor label [:required])
        ;; required, string
        question (r/cursor label [:question])
        ;;
        definition (r/cursor label [:definition])
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
     {:on-submit
      (util/wrap-user-event
       #(if (not (labels-synced?))
          ;; save on server
          (sync-to-server)
          ;; just reset editing
          (reset! (r/cursor label [:editing?]) false))
       :prevent-default true)}
     [:div.field {:class (when (:value-type @errors) "error")}
      [:label "Type"]
      [:select.ui.dropdown
       (if true #_ on-server?
           {:default-value @value-type
            :class "disabled"}
           {:value @value-type
            :on-change
            (util/wrap-user-event
             #(do
                ;; change the type
                (reset! value-type (-> % .-target .-value))
                ;; clear the current answer as it is likely nonsense
                ;; for this type
                (reset! answer (default-answer @value-type))
                ;; reset the definition of the label as well
                (condp = @value-type
                  "boolean"     (reset! definition {:inclusion-values [true]})
                  "string"      (reset! definition {:multi? false})
                  "categorical" (reset! definition {:inclusion-values []
                                                    :multi? true})))
             :timeout false)})
       [:option {:value "boolean"}
        "Boolean"]
       [:option {:value "string"}
        "String"]
       [:option {:value "categorical"}
        "Categorical"]]
      (when-let [error (:value-type @errors)]
        [:div error-message-class
         error])]
     ;; short-label
     [ui/TextInputField
      {:error (:short-label @errors)
       :value short-label
       :on-change #(reset! short-label (-> % .-target .-value))
       :label "Display Name"}]
     ;; required
     [ui/LabeledCheckboxField
      {:error (:required @errors)
       :on-change #(reset! required (-> % .-target .-checked boolean))
       :checked? @required
       :label "Must be answered?"}]
     ;; multi?
     (when (= @value-type "string")
       [ui/LabeledCheckboxField
        {:error (get-in @errors [:definition :multi?])
         :on-change #(reset! multi? (-> % .-target .-checked boolean))
         :checked? @multi?
         :label "Allow multiple values?"}])
     ;; question
     [ui/TextInputField
      {:error (:question @errors)
       :value question
       :on-change #(reset! question (-> % .-target .-value))
       :label "Question"}]
     ;; max-length on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        {:error (get-in @errors [:definition :max-length])
         :value max-length
         :on-change (fn [event]
                      (let [value (-> event .-target .-value)
                            parse-value (if (util/parse-to-number? value)
                                          (sutil/parse-integer value)
                                          value)]
                        (reset! max-length parse-value)))
         :placeholder "100"
         :label "Max Length"}])
     ;; examples on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        {:error (get-in @errors [:definition :examples])
         :default-value (str/join "," @examples)
         :type "text"
         :placeholder "example one,example two"
         :on-change (fn [event]
                      (let [value (-> event .-target .-value)]
                        (if (empty? value)
                          (reset! examples nil)
                          (reset! examples
                                  (str/split value #",")))))
         :label "Examples (comma separated)"}])
     ;; all-values on a categorical label
     (when (= @value-type "categorical")
       (doall
        (list
         ^{:key [:categories-all name]}
         [ui/TextInputField
          {:error (get-in @errors [:definition :all-values])
           :default-value (str/join "," @all-values)
           :placeholder "one,two,three"
           :on-change (fn [event]
                        (let [value (-> event .-target .-value)]
                          (if (empty? value)
                            (reset! all-values nil)
                            (reset! all-values
                                    (str/split value #",")))))
           :label "Categories (comma separated options)"}]
         ;; inclusion values for categorical
         (when (not (empty? @all-values))
           ^{:key [:categories-inclusion name]}
           [:div.field
            {:class (when (get-in @errors [:definition :all-values])
                      "error")}
            [:label "Values for Inclusion"]
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
            (when-let [error (get-in @errors [:definition :inclusion-values])]
              [:div error-message-class
               error])]))))
     ;; inclusion-values on a boolean label
     (when (= @value-type "boolean")
       [:div.field
        {:class (when (get-in @errors [:definition :inclusion-values])
                  "error")}
        [:label "Value for Inclusion"]
        [ui/LabeledCheckbox
         {:checked? (contains? (set @inclusion-values) false)
          :on-change (fn [event]
                       (let [checked? (-> event .-target .-checked)]
                         (reset! inclusion-values
                                 (if checked?
                                   [false]
                                   []))))
          :label "No"}]
        [ui/LabeledCheckbox
         {:checked? (contains? (set @inclusion-values) true)
          :on-change (fn [event]
                       (let [checked? (-> event .-target .-checked)]
                         (reset! inclusion-values
                                 (if checked?
                                   [true]
                                   []))))
          :label "Yes"}]
        (when-let [error (get-in @errors [:definition :inclusion-values])]
          [:div error-message-class
           error])])
     [:div.field
      [:div.ui.two.column.grid
       [:div.column [SaveLabelButton label]]
       [:div.column [DisableEnableLabelButton label]]]]]))

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
           :on-click (util/wrap-user-event
                      #(reset! value [""]))}
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
                                          (util/desktop-size?))
                                     (>= (count all-values) 40))
                               "search dropdown" "dropdown")]
          [:div.ui.fluid.multiple.selection
           {:id dom-id
            :class dropdown-class
            ;; hide dropdown on click anywhere in main dropdown box
            :on-click
            (util/wrap-user-event
             #(when (or (= dom-id (-> % .-target .-id))
                        (-> (js/$ (-> % .-target))
                            (.hasClass "default"))
                        (-> (js/$ (-> % .-target))
                            (.hasClass "label")))
                (let [dd (js/$ (str "#" dom-id))]
                  (when (.dropdown dd "is visible")
                    (.dropdown dd "hide"))))
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
                label-id category required]}
        @label
        on-click-help (util/wrap-user-event
                       #(do nil) :timeout false)]
    [:div.ui.column.label-edit {:class (when required "required")}
     [:div.ui.middle.aligned.grid.label-edit
      [ui/with-tooltip
       (let [name-content
             [:span.name
              {:class (when (>= (count short-label) 30)
                        "small-text")}
              [:span.inner (str short-label)]]]
         (if (and (util/mobile?) (>= (count short-label) 30))
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
        [side-width center-width] (if (util/mobile?)
                                    ["two wide" "twelve wide"]
                                    ["one wide" "fourteen wide"])]
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
                  (->> @labels
                       (filter
                        (fn [[label-id label]]
                          (uuid? label-id)))
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
       (doall (map-indexed
               (fn [i label-id]
                 ^{:key [:label-item label-id]}
                 ;; let's pass a cursor to the state
                 [LabelItem i (r/cursor state [:labels label-id])])
               active-ids))
       (when (not-empty disabled-ids)
         [:div
          [:h4.ui.block.header "Disabled Labels"]
          (doall (map-indexed
                  (fn [i label-id]
                    ^{:key [:label-id label-id]}
                    ;; let's pass a cursor to the state
                    [LabelItem i (r/cursor state [:labels label-id])])
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
