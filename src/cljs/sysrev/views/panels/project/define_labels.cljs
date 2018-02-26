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
   [sysrev.util :refer [desktop-size?]]))

(def panel [:project :project :labels :edit])

(def state (r/atom {}))

(defn- get-local-labels []
  (get-in @state [:labels]))

(defn- set-labels [labels]
  (swap! state assoc-in [:labels] labels))

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
                             (assoc label-value :answer true)
                             (assoc label-value :answer ["bar"])))]
    (apply merge (map (fn [item]
                        (let [label-id (first item)
                              label-value (second item)]
                          (hash-map label-id (insert-answer-fn label-value))))
                      labels))))

(defn sync-local-labels!
  "Sync the local state labels with the app-db labels"
  []
  (reset! state
          (assoc-in @state [:labels]
                    (add-answers (saved-labels)))))

#_ (defn- active-labels
  "Get the current local state of the labels."
  []
  (if (and (nil? (get-labels))
           (not-empty (saved-labels)))
    (do (set-labels (add-answers (saved-labels)))
        (get-labels))
    (get-labels)))

#_(defn- labels-changed? []
    (not= (active-labels) (saved-labels)))

#_ (defn- revert-changes []
     (set-labels (saved-labels)))

#_ (defn- add-empty-label []
  (let [active (active-labels)]
    (set-labels (vec (concat active [nil])))))

#_ (defn- delete-label-index [i]
     (let [active (active-labels)]
       (set-labels (vec (concat (->> active (take i))
                                (->> active (drop (inc i))))))))

(defn- DeleteLabelButton [i label]
  [:div.ui.small.orange.icon.button
   {:on-click #(.log js/console "I would delete a label")
    :style {:margin "0"}}
   [:i.remove.icon]])

(defn- AddLabelButton []
  [:div.ui.fluid.large.icon.button
   {:on-click #(.log js/console "I would add a label")}
   [:i.plus.circle.icon]
   "Add Label"])

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

(defn BooleanLabelForm
  "props
  {:value     <r/atom>   ; atom resolves to boolean}"
  [{:keys [value]}]
  (fn []
    [ui/three-state-selection
     {:set-answer! #(reset! value %)
      :value value}]))

(defn StringLabelForm
  [label]
  (let [{:keys [label-id]} label
        value (r/atom "")]
    (fn []
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
                   (reset! value ""))}
                [:i.fitted.remove.icon]]])
            [:input
             {:type "text"
              :name (str label-id)
              :value @value
              :on-change
              (fn [ev]
                (reset! value (-> ev .-target .-value)))}]]]]]))))

(defn CategoricalLabelForm
  [label]
  (let [{:keys [definition label-id required]} label
        all-values (:all-values definition)
        current-values (r/atom (list))]
    (r/create-class
     {:component-did-mount
      (fn [c]
        (-> (js/$ (r/dom-node c))
            (.dropdown
             (clj->js
              {:onAdd
               (fn [v t]
                 (swap! current-values conj v))
               :onRemove
               (fn [v t]
                 (swap! current-values #(remove (partial = v) %)))
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
             :value (str/join "," @current-values)
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
                [:span.inner short-label]]]
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
        [label-help-popup label-id]
        [:div.ui.row.label-edit-value
         {:class (case value-type
                   "boolean"      "boolean"
                   "categorical"  "category"
                   "string"       "string"
                   "")}
         [:div.inner
          (condp = value-type
            "boolean"
            (let [current-answer-cursor (r/cursor state [:labels label-id :answer])]
              [BooleanLabelForm
               {:value current-answer-cursor}])
            "string"
            [StringLabelForm label]
            "categorical"
            [CategoricalLabelForm label]
            (pr-str label))]]]])))

(defn- LabelItem [i label]
  [:div.ui.middle.aligned.grid.segment.label-item
   [:div.row
    [ui/CenteredColumn
     [:div.ui.blue.label (str (inc i))]
     "one wide center aligned column label-index"]
    [:div.fourteen.wide.column
     [Label label]]
    [ui/CenteredColumn
     [DeleteLabelButton i label]
     "one wide center aligned column delete-label"]]])


(defmethod panel-content panel []
  (fn [child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
          ;;active (vals (active-labels))
          ]
      (when (empty? (get-local-labels))
        (sync-local-labels!))
      ;;(.log js/console (clj->js (get-local-labels)))
      [:div.define-labels
       [:div
        (doall (map-indexed
                (fn [i label]
                  ^{:key i}
                  [LabelItem i label])
                (sort-by :label-id-local (vals (get-local-labels)))
                ))]
       [AddLabelButton]
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



