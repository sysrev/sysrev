(ns sysrev.views.panels.project.define-labels
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw
     reg-event-db reg-event-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [sysrev.subs.labels :as labels]
   [sysrev.util :refer [mobile?]]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :as ui]
   [sysrev.views.review :refer [label-help-popup]]
   [sysrev.shared.util :refer [in?]]
   [sysrev.util :refer [desktop-size?]]))

(def panel [:project :project :labels :edit])

(defonce state (r/cursor app-db [:state :panels panel]))

(defn- get-labels []
  (get-in @state [:labels]))

(defn- set-labels [labels]
  (swap! state assoc-in [:labels] labels))

(defn- saved-labels []
  (let [db @app-db]
    (->> (labels/project-label-ids @app-db)
         (mapv #(labels/get-label-raw db %)))))

(defn- active-labels []
  (if (and (nil? (get-labels))
           (not-empty (saved-labels)))
    (do (set-labels (saved-labels))
        (get-labels))
    (get-labels)))

(defn- labels-changed? []
  (not= (active-labels) (saved-labels)))

(defn- revert-changes []
  (set-labels (saved-labels)))

(defn- add-empty-label []
  (let [active (active-labels)]
    (set-labels (vec (concat active [nil])))))

(defn- delete-label-index [i]
  (let [active (active-labels)]
    (set-labels (vec (concat (->> active (take i))
                             (->> active (drop (inc i))))))))

(defn- DeleteLabelButton [i label]
  [:div.ui.small.orange.icon.button
   {:on-click #(delete-label-index i)
    :style {:margin "0"}}
   [:i.remove.icon]])

(defn- AddLabelButton []
  [:div.ui.fluid.large.icon.button
   {:on-click #'add-empty-label}
   [:i.plus.circle.icon]
   "Add Label"])

(defn- SaveButton []
  (let [changed? (labels-changed?)]
    [:div.ui.large.right.labeled.positive.icon.button
     {:on-click nil
      :class (if changed? "" "disabled")}
     "Save Labels"
     [:i.check.circle.outline.icon]]))

(defn- CancelButton []
  (let [changed? (labels-changed?)]
    [:div.ui.large.right.labeled.icon.button
     {:on-click #'revert-changes
      :class (if changed? "" "disabled")}
     "Cancel"
     [:i.undo.icon]]))

(defn- SaveCancelButtons []
  [:div.ui.center.aligned.grid>div.row>div.ui.sixteen.wide.column
   [SaveButton] [CancelButton]])

(defn BooleanLabel
  [label]
  (let [value (r/atom true)]
    (fn []
      [ui/three-state-selection
       #(reset! value (not @value))
       @value])))

(defn StringLabel
  [label]
  (let [{:keys [value-type question short-label label-id]} label
        value (r/atom "")]
    (.log js/console "StringLabel rendered")
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

(defn CategoricalLabel
  [label]
  (let [{:keys [definition label-id required]} label
        all-values (:all-values definition)
        inclusion-values (:inclusion-values definition)
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
    (let [{:keys [value-type question short-label label-id]} label]
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
              ;;[inclusion-tag article-id label-id]
              [:span.name " "]
              (when (not-empty question)
                [:i.right.floated.fitted.grey.circle.question.mark.icon])
              [:div.clear name-content]]
             [:div.ui.row.label-edit-name
              ;;[inclusion-tag article-id label-id]
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
            [BooleanLabel label]
            "string"
            [StringLabel label]
            "categorical"
            [CategoricalLabel label]
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
          active (active-labels)]
      (.log js/console (clj->js active))
      [:div.define-labels
       [:div
        (doall (map-indexed
                (fn [i label]
                  ^{:key i}
                  [LabelItem i label])
                (sort-by :label-id-local active)))]
       [AddLabelButton]
       [:div.ui.divider]
       [SaveCancelButtons]])))

;; I really like: https://github.com/kevinchappell/formBuilder
;; https://jsfiddle.net/kevinchappell/ajp60dzk/5/
;; however, it is a jQuery library =(
