(ns sysrev.views.panels.project.define-labels
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw
     reg-event-db reg-event-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [sysrev.subs.labels :as labels]
   [sysrev.subs.project :refer [active-project-id]]
   [sysrev.util :refer [mobile?]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :as ui]
   [sysrev.views.review :refer [label-help-popup inclusion-tag]]
   [sysrev.shared.util :refer [in?]]
   [sysrev.util :refer [desktop-size? random-id]]))

(def panel [:project :project :labels :edit])

(def state (r/atom {}))

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
  (let [label-id (str "new-label-" (random-id))]
    {:definition
     (cond-> {}
       (= value-type "boolean") (assoc :inclusion-values [true])
       (= value-type "categorical") (assoc :inclusion-values [])),
     ;; we are assuming people want all labels as inclusion criteria
     :category "inclusion criteria"
     :multi? false
     :project-ordering (inc (max-project-ordering)),
     :label-id label-id
     :project-id (active-project-id @app-db),
     :enabled true,
     :value-type value-type
     :required true
     :answer (default-answer value-type)}))


(defn- set-labels [labels]
  (swap! state assoc-in [:labels] labels))

(defn add-new-label!
  "Add a new label to the local state"
  [label]
  (swap! state assoc-in [:labels (:label-id label)] label))

(defn- saved-labels
  "Get the label values for project from the app-db"
  []
  (let [db @app-db]
    (get-in @app-db [:data :project (active-project-id @app-db) :labels])))

(defn add-answers
  "Add default answers to each label"
  [labels]
  (let [insert-answer-fn (fn [label-value]
                           (condp = (:value-type label-value)
                             "boolean"
                             (assoc label-value :answer nil)
                             (assoc label-value :answer [])))
        insert-editing? (fn [label-value]
                          (assoc label-value :editing? false))]
    (apply merge (map (fn [item]
                        (let [label-id (first item)
                              label-value (second item)]
                          (hash-map label-id ((comp insert-answer-fn insert-editing?) label-value))))
                      labels))))

(defn sync-local-labels!
  "Sync the local state labels with the app-db labels"
  []
  (reset! state
          (assoc-in @state [:labels]
                    (add-answers (saved-labels)))))

(defn- DeleteLabelButton [label]
  (let [editing?  (r/cursor state [:labels (:label-id label) :editing?])]
    [:div.ui.small.orange.icon.button
     {:on-click #(cond @editing?
                       (reset! editing? false)
                       (and (not @editing?)
                            (string? (:label-id label)))
                       (swap! (r/cursor state [:labels]) dissoc (:label-id label)))
      :style {:margin "0"}}
     [:i.remove.icon]]))

(defn EditLabelButton [label]
  [:div.ui.small.primary.icon.button
   {:on-click #(swap! (r/cursor state [:labels (:label-id label) :editing?]) not)
    :style {:margin "0"}}
   [:i.edit.icon]])


(defn- AddLabelButton
  "Add a label of type"
  [value-type]
  [:div.ui.fluid.large.icon.button
   ;; note: the condp may not be needed eventually
   {:on-click #(condp = value-type
                 "boolean"
                 (add-new-label! (create-blank-label "boolean"))
                 "string"
                 (add-new-label! (create-blank-label "string"))
                 "categorical"
                 (add-new-label! (create-blank-label "categorical")))}
   [:i.plus.circle.icon]
   (str "Add " (str/capitalize value-type) " Label")])



(defn- SaveButton []
  (let [;;changed? (labels-changed?)
        ]
    [:div.ui.large.right.labeled.positive.icon.button
     {:on-click #(.log js/console "I would save")
      :class "disabled" ;;(if changed? "" "disabled")
      }
     "Save Labels"
     [:i.check.circle.outline.icon]]))

(defn- CancelButton []
  (let [;;changed? (labels-changed?)
        ]
    [:div.ui.large.right.labeled.icon.button
     {:on-click #(.log js/console "I would cancel")
      :class "disabled";;(if changed? "" "disabled")
      }
     "Cancel"
     [:i.undo.icon]]))

(defn- SaveCancelButtons []
  [:div.ui.center.aligned.grid>div.row>div.ui.sixteen.wide.column
   [SaveButton] [CancelButton]])

(defn InclusionTag
  [label]
  (fn [{:keys [category answer definition value-type]}]
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
          color (case inclusion
                  true   "green"
                  false  "orange"
                  nil    "grey")
          iclass (case inclusion
                   true   "circle plus icon"
                   false  "circle minus icon"
                   nil    "circle outline icon")]
      (if criteria?
        [:i.left.floated.fitted {:class (str color " " iclass)}]
        [:i.left.floated.fitted {:class "grey content icon"
                                 :style {} #_ (when-not boolean-label?
                                                {:visibility "hidden"})}]))))

(defn LabelEditForm
  [{:keys [label-id]}]
  (let [label-style {:display "block"
                     :margin-top "0.5em"
                     :margin-bottom "0.5em"}
        input-style {:margin-left "0.5em"}
        label (r/cursor state [:labels label-id])
        value-type (r/cursor label [:value-type])
        answer (r/cursor label [:answer])
        ;; name is redundant with label-id
        name (r/cursor label [:name])   ; required, string, ✓
        short-label (r/cursor label [:short-label]) ; required, string, ✓
        required (r/cursor label [:required]) ; required, boolean, ✓
        question (r/cursor label [:question]) ; required, string, ✓
        definition (r/cursor label [:definition]) ; not used, ✓
        ;; boolean
        inclusion-values (r/cursor definition [:inclusion-values]) ; required, vector of booleans
        ;; string
        examples (r/cursor definition [:examples]) ; optional, vector of strings, ✓
        max-length (r/cursor definition [:max-length]) ; required, integer, ✓
        multi? (r/cursor definition [:multi?]) ; required (also in categorical), boolean
        ;; categorical
        all-values (r/cursor definition [:all-values]) ; required, vector of strings
        inclusion-values (r/cursor definition [:inclusion-values]) ; optional, vector of string, must be in all-values
        ]
    (fn []
      [:div
       [:label {:style label-style}
        "Type of Label"
        [:select {:class "ui dropdown"
                  :style input-style
                  :on-change #(do
                                ;; need to change the type
                                (reset! value-type
                                        (-> % .-target .-value))
                                ;; but also clear the current answer as it is likely nonsense
                                (reset! answer (default-answer @value-type)))
                  :value @value-type}
         [:option {:value "boolean"}
          "Boolean"]
         [:option {:value "string"}
          "String"]
         [:option {:value "categorical"}
          "Categorical"]]]
       ;; this is going to be filled in some other way, or the field
       ;; deleted
       #_ [:label {:style label-style} "Name"
           [:div.ui.fluid.input
            [:input {:value @name
                     :type "text"
                     :on-change (fn [event]
                                  (reset! name
                                          (-> event .-target .-value)))}]]]
       ;; short-label
       [:label {:style label-style} "Display Label"
        [:div.ui.fluid.input
         [:input {:value @short-label
                  :type "text"
                  :on-change (fn [event]
                               (reset! short-label
                                       (-> event .-target .-value)))}]]]
       ;; required
       [:label {:style label-style} "Required"
        [:input {:style input-style
                 :checked @required
                 :type "radio"
                 :on-change (fn [event]
                              (swap! required
                                     not))}]]
       ;; question
       [:label {:style label-style} "Question"
        [:div.ui.fluid.input
         [:input {:value @question
                  :type "text"
                  :on-change (fn [event]
                               (reset! question
                                       (-> event .-target .-value)))}]]]
       ;; inclusion-values
       #_ (when (= @value-type "boolean")
            [:label "Which values for inclusion?"
             [:input {:value @foo}]])
       (when (= @value-type "string")
         ;; max-length
         [:label {:style label-style} "Max Length"
          [:div.ui.fluid.input
           [:input {:value @max-length
                    :type "text"
                    :on-change (fn [event]
                                 (reset! max-length
                                         (-> event .-target .-value)))}]]])
       (when (= @value-type "string")
         ;; examples
         [:label {:style label-style} "Examples (comma separated)"
          [:div.ui.fluid.input
           [:input {:default-value (str/join "," @examples)
                    :type "text"
                    :on-change
                    (fn [event]
                      (let [value (-> event .-target .-value)]
                        (if (empty? value)
                          (reset! examples nil)
                          (reset! examples
                                  (str/split value #",")))))}]]])
       (when (= @value-type "categorical")
         ;; all-values
         [:label {:style label-style} "Categories (comma separated options)"
          [:div.ui.form
           [:input {:default-value (str/join "," @all-values)
                    :type "text"
                    :placeholder "Heart,Liver,Brain"
                    :on-change
                    (fn [event]
                      (let [value (-> event .-target .-value)]
                        (if (empty? value)
                          (reset! all-values nil)
                          (reset! all-values
                                  (str/split value #",")))))}]]])
       (when (= @value-type "boolean")
         [:div
          [:label {:style label-style} "Inclusion Criteria"]
          [:div.ui.checkbox
           [:input {:type "checkbox"
                    :on-change (fn [event]
                                 (let [checked? (-> event .-target .-checked)]
                                   (reset! inclusion-values
                                           (if checked?
                                             (into [] (conj (set @inclusion-values) false))
                                             (into [] (remove false? (set @inclusion-values)))))))
                    :checked (contains? (set @inclusion-values) false)}]
           [:label {:style {:margin-right "0.5em"}} "No"]]
          [:div.ui.checkbox
           [:input {:type "checkbox"
                    :on-change (fn [event]
                                 (let [checked? (-> event .-target .-checked)]
                                   (reset! inclusion-values
                                           (if checked?
                                             (into [] (conj (set @inclusion-values) true))
                                             (into [] (remove true? (set @inclusion-values)))))))
                    :checked (contains? (set @inclusion-values) true)}]
           [:label {:style {:margin-right "0.5em"}} "Yes"]]])
       ])))

(defn StringLabelForm
  [{:keys [set-answer! value label-id]}]
  (let [left-action? true]
    ^{:key [label-id]}
    [:div.inner
     [:div.ui.form.string-label
      [:div.field.string-label
       {:class "" }
       [:div.ui.fluid
        {:class "labeled right action input"}
        (when left-action?
          [:div.ui.label.input-remove
           [:div.ui.icon.button
            {:class " "
             :on-click
             (fn [ev]
               (reset! value [""]))}
            [:i.fitted.remove.icon]]])
        [:input
         {:type "text"
          :name (str label-id)
          :value @value
          :on-change set-answer!}]]]]]))

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
      (fn []
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
                                          (desktop-size?))
                                     (>= (count all-values) 40))
                               "search dropdown" "dropdown")]
          [:div.ui.fluid.multiple.selection
           {:id dom-id
            :class dropdown-class
            ;; hide dropdown on click anywhere in main dropdown box
            :on-click #(when (or (= dom-id (-> % .-target .-id))
                                 (-> (js/$ (-> % .-target))
                                     (.hasClass "default"))
                                 (-> (js/$ (-> % .-target))
                                     (.hasClass "label")))
                         (let [dd (js/$ (str "#" dom-id))]
                           (when (.dropdown dd "is visible")
                             (.dropdown dd "hide"))))}
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
  []
  (fn [label]
    (let [{:keys [value-type question short-label label-id category]} label]
      [:div.ui.column.label-edit {:class ;;label-css-class
                                  "required"
                                  }
       [:div.ui.middle.aligned.grid.label-edit
        [ui/with-tooltip
         (let [name-content
               [:span.name
                {:class (when (>= (count short-label) 30)
                          "small-text")}
                [:span.inner (str short-label " is " label-id)]]]
           (if (and (mobile?) (>= (count short-label) 30))
             [:div.ui.row.label-edit-name
              [InclusionTag label]
              [:span.name " "]
              (when (not-empty question)
                [:i.right.floated.fitted.grey.circle.question.mark.icon])
              [:div.clear name-content]]
             [:div.ui.row.label-edit-name
              [InclusionTag label]
              name-content
              (when (not-empty question)
                [:i.right.floated.fitted.grey.circle.question.mark.icon])]))
         {:variation "basic"
          :delay {:show 400, :hide 0}
          :hoverable false
          :inline true
          :position #_(cond
                        (= row-position :left)
                        "top left"
                        (= row-position :right)
                        "top right"
                        :else
                        "top center")
          "top center"
          :distanceAway 8}]
        [label-help-popup label]
        [:div.ui.row.label-edit-value
         {:class (case value-type
                   "boolean"      "boolean"
                   "categorical"  "category"
                   "string"       "string"
                   "")}
         [:div.inner
          (let [value (r/cursor state [:labels label-id :answer])
                {:keys [label-id definition required]} label]
            (condp = value-type
              "boolean"
              [ui/three-state-selection {:set-answer! #(reset! value %)
                                         :value value}]
              "string"
              [StringLabelForm {:value value
                                :set-answer!
                                #(reset! value [(-> % .-target .-value)])
                                :label-id label-id}]
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
              (pr-str label)))]]]])))

(defn- LabelItem []
  (let [hovering? (r/atom false)]
    (fn [i label]
      (let [label (r/cursor state [:labels (:label-id label)])]
        [:div.ui.middle.aligned.grid.segment.label-item
         {:on-mouse-enter #(reset! hovering? true)
          :on-mouse-leave #(reset! hovering? false)}
         [:div.row
          [ui/CenteredColumn
           [:div.ui.blue.label (str (inc i))]
           "one wide center aligned column label-index"]
          [:div.fourteen.wide.column
           (if @(r/cursor label [:editing?])
             [LabelEditForm @label]
             [Label @label])]
          [ui/CenteredColumn
           (when @hovering?
             [:div
              [DeleteLabelButton @label]
              [EditLabelButton @label]])
           "one wide center aligned column delete-label"]]]))))


(defmethod panel-content panel []
  (fn [child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
          labels (r/cursor state [:labels])]
      (when (empty? @labels)
        (sync-local-labels!))
      [:div.define-labels
       (doall (map-indexed
               (fn [i label]
                 ^{:key i}
                 [LabelItem i label])
               (sort-by :project-ordering (vals @labels))))
       [AddLabelButton "boolean"]
       [:br]
       [AddLabelButton "string"]
       [:br]
       [AddLabelButton "categorical"]
       [:div.ui.divider]
       [SaveCancelButtons]])))

;; I really like: https://github.com/kevinchappell/formBuilder
;; https://jsfiddle.net/kevinchappell/ajp60dzk/5/
;; however, it is a jQuery library =(


;; state will have the labels in the form
;; {<uuid> <label-map>, ... }

;; we are reworking it this way because the labels on articles are of the form
;; {<user-id> {<label-uuid> {:answer <string>, :resolve <boolean>, :confirmed <boolean>, :confirm-epoch <int>} ..}}
;;
;; we want to have some congruence between sysrev.views.review and sysrev.views.panels.project.define-labels
;; we need to add an :answer to each label (boolean value for bools, empty vector for string/categories) for the local
;; state atom,so they are similar

;; we will also need to distinguish between "saved" labels ... labels which are on the server, and labels which are being created/modified in the client

;; when the label definitions tab is opened, the local namespace labels should be  updated to reflect the client definitions
;; if the save button is clicked, the label definitions will be sent to the server
;; if there are no errors, the label definitions are fetched from the server and updated in the client
;; if there are errors, errors are added to the local namespace labels for the user to correct and save again.


;; the current task is to make Forms which will accept the on-change/value and are independent of whether they are label definitions or article labels
;; this has been started with boolean. currently, this form doesn't seem to be updating with the proper 'on-change' fn

;; After that, there is the need to better emulate the indicator for required/unrequired... this namespace does not currently do that

;; once these basics are done, syncing with the server and the client state is the next step. 



