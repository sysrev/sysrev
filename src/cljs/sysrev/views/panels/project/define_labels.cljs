(ns sysrev.views.panels.project.define-labels
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw
     reg-event-db reg-event-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [sysrev.action.core :refer [def-action]]
   [sysrev.subs.labels :as labels]
   [sysrev.subs.project :refer [active-project-id]]
   [sysrev.util :refer [mobile?]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :as ui]
   [sysrev.views.review :refer [label-help-popup inclusion-tag]]
   [sysrev.shared.util :refer [in?]]
   [sysrev.util :refer [desktop-size? random-id string->integer parse-to-number?]]))

;; Convention -
;; A (new) label that exists in the client but not on the server
;; has a label-id of type string
;;
;; After it is saved on the server, the label-id type is #object[Transit$UUID] on the client and java.util.UUID on the server

;; The jQuery plugin formBuilder is inspiration for the UI
;; repo: https://github.com/kevinchappell/formBuilder
;; demo: https://jsfiddle.net/kevinchappell/ajp60dzk/5/

(def panel [:project :project :labels :edit])

(def initial-state {:server-syncing? false})

(def state (r/cursor app-db [:state :panels :project :define-labels]))

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
  (let [label-id (str "new-label-" (random-id))]
    {:definition
     (cond-> {}
       (= value-type "boolean") (assoc :inclusion-values [true])
       (= value-type "string") (assoc :multi? false)
       (= value-type "categorical") (assoc :inclusion-values []
                                           :multi? false)),
     ;; we are assuming people want all labels as inclusion criteria
     ;; label influences inclusion
     :category "inclusion criteria"
     :name (str value-type (random-id))
     :project-ordering (inc (max-project-ordering))
     ;; string, per convention
     :label-id label-id
     :project-id (active-project-id @app-db)
     :enabled true
     :value-type value-type
     ;; must be answered
     :required true
     ;; below are values for editing labels
     :answer (default-answer value-type)
     :editing? true
     :hovering? false
     :errors (list)
     }))

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
                             (assoc :editing? false)
                             (assoc :hovering? false)))]
    (map-labels (comp insert-answer-fn add-local-keys)
                labels)))

(defn sync-local-labels!
  "Sync the local state labels with labels map. Labels should already have the proper local keys added to them via labels->local-labels"
  [labels]
  (swap! state
         assoc-in [:labels]
         labels))

(defn errors-in-labels?
  "Are there errors in the labels?"
  [labels]
  (not (every? nil? (map :errors (vals labels)))))

(defn local-labels->global-labels
  [labels]
  (->> labels
       vals
       (map #(dissoc % :editing? :hovering? :answer :errors))
       (map #(hash-map (:label-id %) %))
       (apply merge)))

(defn labels-synced?
  "Are the labels synced with the global app-db?"
  []
  (= (saved-labels)
     (local-labels->global-labels (get-local-labels))))

(defn sync-app-db-labels!
  "Sync the local labels with the global labels. label maps in labels
  should not have local keywords such as :editing?, :errors, etc"
  [labels]
  ;; set the global labels
  (reset!
   (r/cursor app-db
             [:data :project (active-project-id @app-db) :labels])
   labels))

(defn- DeleteLabelButton [label]
  (let [editing?  (r/cursor label [:editing?])
        label-id  (r/cursor label [:label-id])
        errors (r/cursor label [:errors])]
    [:div.ui.small.orange.icon.button
     {:on-click #(cond @editing?
                       (reset! editing? false)
                       (string? @label-id)
                       (swap! (r/cursor state [:labels]) dissoc @label-id)
                       :else
                       (reset! (r/cursor label [:enabled]) false))
      :style {:margin "0"}}
     [:i.remove.icon]]))

(defn EditLabelButton
  "label is a cursor into the state representing the label"
  [label]
  [:div.ui.small.primary.icon.button
   {:on-click #(swap! (r/cursor label [:editing?]) not)
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
             {}
             ))

(defn- SaveButton []
  [:div.ui.large.right.labeled.positive.icon.button
   {:on-click #(dispatch [:action [:labels/sync-project-labels (active-project-id @app-db) (local-labels->global-labels (get-local-labels))]])
    :class (if (and (or (not (labels-synced?))
                        (errors-in-labels? (get-local-labels)))
                    (not @(r/cursor state [:server-syncing?])))
             "enabled"
             "disabled")}
   "Save Labels"
   [:i.check.circle.outline.icon]])

(defn- CancelButton []
  [:div.ui.large.right.labeled.icon.button
   {:on-click #(sync-local-labels! (labels->local-labels (saved-labels)))
    :class (if (and (not (labels-synced?))
                    (not @(r/cursor state [:server-syncing?])))
             "enabled"
             "disabled")}
   "Cancel"
   [:i.undo.icon]])

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

(defn TextInput
  "Props:
  {:error         <string>       ; error message, optional
   :value         <reagent atom> ; value, optional
   :on-change     <fn>           ; a fn of event
   :placeholder   <string>       ; optional
   :default-value <string>       ; optional
   :label         <string>       ; label value
  }"
  [{:keys [error value on-change placeholder default-value label]}]
  [:div {:class (cond-> "field "
                  error (str "error"))}
   [:label label]
   [:div.ui.form
    [:input (cond-> {:type "text"
                     :on-change on-change}
              (not (nil? default-value)) (merge {:default-value default-value})
              (and (nil? default-value)
                   (not (nil? value))) (merge {:value @value})
              (not (nil? placeholder)) (merge {:placeholder placeholder}))]]
   (when error
     [:div {:class "ui red message"}
      error])])

(defn LabelEditForm
  []
  (let []
    (fn [label]
      (let [label-style {:display "block"
                         :margin-top "0.5em"
                         :margin-bottom "0.5em"}
            input-style {:margin-left "0.5em"}
            error-message-class {:class "ui red message"}
            value-type (r/cursor label [:value-type])
            answer (r/cursor label [:answer])
            ;; name is redundant with label-id
            name (r/cursor label [:name]) ; required, string
            short-label (r/cursor label [:short-label]) ; required, string
            required (r/cursor label [:required]) ; required, boolean
            question (r/cursor label [:question]) ; required, string
            definition (r/cursor label [:definition])
            ;; boolean
            inclusion-values (r/cursor definition [:inclusion-values]) ; required, vector of booleans
            ;; string
            examples (r/cursor definition [:examples]) ; optional, vector of strings
            max-length (r/cursor definition [:max-length]) ; required, integer
            multi? (r/cursor definition [:multi?]) ; required (also in categorical), boolean
            ;; categorical
            all-values (r/cursor definition [:all-values]) ; required, vector of strings
            inclusion-values (r/cursor definition [:inclusion-values]) ; optional, vector of string, must be in all-values
            ;; errors
            errors (r/cursor label [:errors])]
        [:div.ui.form
         [:div {:class (cond-> "field "
                         (:value-type @errors) (str " error"))}
          [:label {:style label-style}
           "Type of Label"
           [:select {:class "ui dropdown"
                     :on-change #(do
                                   ;; change the type
                                   (reset! value-type
                                           (-> % .-target .-value))
                                   ;; clear the current answer as it is likely nonsense
                                   ;; for this type
                                   (reset! answer (default-answer @value-type))
                                   ;; reset the definition of the label as well
                                   (condp = @value-type
                                     "boolean"     (reset! definition {:inclusion-values [true]})
                                     "string"      (reset! definition {:multi? false})
                                     "categorical" (reset! definition {:inclusion-values []
                                                                       :multi? true})))
                     :value @value-type}
            [:option {:value "boolean"}
             "Boolean"]
            [:option {:value "string"}
             "String"]
            [:option {:value "categorical"}
             "Categorical"]]]
          (when-let [error (:value-type @errors)]
            [:div error-message-class
             error])]
         ;; short-label
         [TextInput {:error (:short-label @errors)
                     :value short-label
                     :on-change (fn [event]
                                  (reset! short-label
                                          (-> event .-target .-value)))
                     :label "Display Label"}]
         ;; required
         [:div {:class (cond-> "field "
                         (:required @errors)
                         (str " error"))}
          [:label {:style label-style} "Must be answered?"
           [:div.ui.checkbox {:style {:margin-left "0.5em"}}
            [:input
             {:type "checkbox"
              :on-change (fn [event]
                           (let [checked? (-> event .-target .-checked)]
                             (reset! required
                                     (if checked?
                                       true
                                       false))))
              :checked @required}]
            [:label {:style {:margin-right "0.5em"}}]]]
          (when-let [error (:required @errors)]
            [:div error-message-class
             error])]
         ;; multi?
         (when (= @value-type "string")
           [:div {:class (cond-> "field "
                           (get-in @errors [:definition :multi?]) (str "error"))}
            [:label {:style label-style} "Allow Multiple Values?"
             [:div.ui.checkbox {:style {:margin-left "0.5em"}}
              [:input {:style input-style
                       :type "checkbox"
                       :on-change (fn [event]
                                    (let [checked? (-> event .-target .-checked)]
                                      (reset! multi?
                                              (if checked?
                                                true
                                                false))))
                       :checked @multi?}]
              [:label {:style {:margin-right "0.5em"}}]]]
            (when-let [error (get-in @errors [:definition :multi?])]
              [:div error-message-class
               error])])
         ;; question
         [TextInput {:error (:question @errors)
                     :value question
                     :on-change (fn [event]
                                  (reset! question
                                          (-> event .-target .-value)))
                     :label "Question"}]
         ;; max-length on a string label
         (when (= @value-type "string")
           [TextInput {:error (get-in @errors [:definition :max-length])
                       :value max-length
                       :on-change (fn [event]
                                    (let [value (-> event .-target .-value)
                                          parse-value (if (parse-to-number? value)
                                                        (string->integer value)
                                                        value)]
                                      (reset! max-length parse-value)))
                       :placeholder "140"
                       :label "Max Length"}])
         ;; examples on a string label
         (when (= @value-type "string")
           [TextInput {:error (get-in @errors [:definition :examples])
                       :default-value (str/join "," @examples)
                       :type "text"
                       :placeholder "Heart,Liver,Brain"
                       :on-change (fn [event]
                                    (let [value (-> event .-target .-value)]
                                      (if (empty? value)
                                        (reset! examples nil)
                                        (reset! examples
                                                (str/split value #",")))))
                       :label "Examples (comma separated)"}])
         ;; all-values on a categorical label
         (when (= @value-type "categorical")
           [:div
            [TextInput {:error (get-in @errors [:definition :all-values])
                        :default-value (str/join "," @all-values)
                        :placeholder "Heart,Liver,Brain"
                        :on-change (fn [event]
                                     (let [value (-> event .-target .-value)]
                                       (if (empty? value)
                                         (reset! all-values nil)
                                         (reset! all-values
                                                 (str/split value #",")))))
                        :label "Categories (comma separated options)"}]
            ;; inclusion values for categorical
            (when (not (empty? @all-values))
              [:div {:class (cond-> "field "
                              (get-in @errors [:definition :all-values])
                              (str "error"))}
               [:label {:style label-style} "Values for Inclusion"]
               (doall (map
                       (fn [option-value]
                         ^{:key (gensym option-value)}
                         [:div.ui.checkbox
                          [:input {:type "checkbox"
                                   :on-change (fn [event]
                                                (let [checked? (-> event .-target .-checked)]
                                                  (reset! inclusion-values
                                                          (if checked?
                                                            (into [] (conj (set @inclusion-values) option-value))
                                                            (into [] (remove #(= option-value %) (set @inclusion-values)))))))
                                   :checked (contains? (set @inclusion-values) option-value)}]
                          [:label {:style {:margin-right "0.5em"}} option-value]])
                       @all-values))
               (when-let [error (get-in @errors [:definition :inclusion-values])]
                 [:div error-message-class
                  error])])])
         ;; inclusion-values on a boolean label
         (when (= @value-type "boolean")
           [:div {:class (cond-> "field "
                           (get-in @errors [:definition :inclusion-values])
                           (str " error"))}
            [:label {:style label-style} "Value for Inclusion"]
            [:div.ui.checkbox
             [:input {:type "checkbox"
                      :on-change (fn [event]
                                   (let [checked? (-> event .-target .-checked)]
                                     (reset! inclusion-values
                                             (if checked?
                                               [false]
                                               []))))
                      :checked (contains? (set @inclusion-values) false)}]
             [:label {:style {:margin-right "0.5em"}} "No"]]
            [:div.ui.checkbox
             [:input {:type "checkbox"
                      :on-change (fn [event]
                                   (let [checked? (-> event .-target .-checked)]
                                     (reset! inclusion-values
                                             (if checked?
                                               [true]
                                               []))))
                      :checked (contains? (set @inclusion-values) true)}]
             [:label {:style {:margin-right "0.5em"}} "Yes"]]
            (when-let [error (get-in @errors [:definition :inclusion-values])]
              [:div error-message-class
               error])])]))))

;; this corresponds to
;; (defmethod sysrev.views.review/label-input-el "string" ...)
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
    (let [{:keys [value-type question short-label label-id category]} @label]
      [:div.ui.column.label-edit {:class ;;label-css-class
                                  "required"
                                  }
       [:div.ui.middle.aligned.grid.label-edit
        [ui/with-tooltip
         (let [name-content
               [:span.name
                {:class (when (>= (count short-label) 30)
                          "small-text")}
                [:span.inner (str short-label)]]]
           (if (and (mobile?) (>= (count short-label) 30))
             [:div.ui.row.label-edit-name
              [InclusionTag @label]
              [:span.name " "]
              (when (not-empty question)
                [:i.right.floated.fitted.grey.circle.question.mark.icon])
              [:div.clear name-content]]
             [:div.ui.row.label-edit-name
              [InclusionTag @label]
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
  "label is a cursor"
  (fn [i label]
    (let [hovering? (r/cursor label [:hovering?])
          editing? (r/cursor label [:editing?])
          errors (r/cursor label [:errors])
          name (r/cursor label [:name])
          label-id (r/cursor label [:label-id])]
      [:div.ui.middle.aligned.grid.segment.label-item
       {:id (str @label-id)
        :on-mouse-enter #(reset! hovering? true)
        :on-mouse-leave #(reset! hovering? false)}
       [:div.row
        [ui/CenteredColumn
         [:div.ui.blue.label (str (inc i))]
         "one wide center aligned column label-index"]
        [:div.fourteen.wide.column
         (if @editing?
           [LabelEditForm label]
           [Label label])]
        [ui/CenteredColumn
         (when (and @hovering?
                    (not= "overall include"
                          @name))
           [:div
            [DeleteLabelButton label]
            [EditLabelButton label]])
         "one wide center aligned column delete-label"]]])))

(defmethod panel-content panel []
  (fn [child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
          labels (r/cursor state [:labels])]
      (when (empty? @state)
        (reset! state initial-state))
      (when (empty? @labels)
        (sync-local-labels! (labels->local-labels (saved-labels))))
      [:div.define-labels
       (doall (map-indexed
               (fn [i label]
                 ^{:key i}
                 ;; let's pass a cursor to the state
                 [LabelItem i (r/cursor state [:labels (:label-id label)])])
               (sort-by :project-ordering (filter :enabled (vals @labels)))))
       [AddLabelButton "boolean"]
       [:br]
       [AddLabelButton "string"]
       [:br]
       [AddLabelButton "categorical"]
       [:div.ui.divider]
       [SaveCancelButtons]])))

(defmethod panel-content [:project :project :labels] []
  (fn [child]
    [:div.project-content
     child]))
