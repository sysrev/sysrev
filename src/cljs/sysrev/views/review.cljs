(ns sysrev.views.review
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub
              reg-event-db reg-event-fx reg-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.nav :refer [nav nav-scroll-top]]
            [sysrev.state.nav :refer [active-panel project-uri]]
            [sysrev.state.review :as review]
            [sysrev.state.labels :refer [get-label-raw]]
            [sysrev.state.notes :as notes]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components :as ui]
            [sysrev.util :refer
             [full-size? mobile? desktop-size? nbsp wrap-prevent-default nbsp]]
            [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn set-label-value [db article-id label-id label-value]
  (assoc-in db [:state :review :labels article-id label-id]
            label-value))

(defn update-label-value [db article-id label-id update-fn]
  (update-in db [:state :review :labels article-id label-id]
             update-fn))

(reg-event-db
 ::set-label-value
 [trim-v]
 (fn [db [article-id label-id label-value]]
   (set-label-value db article-id label-id label-value)))

;; Adds a value to an active label answer vector
(reg-event-db
 ::add-label-value
 [trim-v]
 (fn [db [article-id label-id label-value]]
   #_ (println (str "running add-label-value (\"" label-value "\")"))
   (let [current-values (get (review/active-labels db article-id) label-id)]
     (set-label-value db article-id label-id
                      (-> current-values (concat [label-value]) distinct vec)))))

;; Removes a value from an active label answer vector
(reg-event-db
 ::remove-label-value
 [trim-v]
 (fn [db [article-id label-id label-value]]
   #_ (println (str "running remove-label-value (\"" label-value "\")"))
   (let [current-values (get (review/active-labels db article-id) label-id)]
     (set-label-value db article-id label-id
                      (->> current-values (remove (partial = label-value)) vec)))))

;; Triggers "set selected" Semantic dropdown action
(reg-fx
 ::select-categorical-value
 (fn [[article-id label-id label-value]]
   (.dropdown
    (js/$ (str "#label-edit-" article-id "-" label-id))
    "set selected"
    label-value)))

(reg-event-db
 ::remove-string-value
 [trim-v]
 (fn [db [article-id label-id value-idx]]
   (update-label-value db article-id label-id
                       #(->> (assoc (vec %) value-idx "")
                             (filterv not-empty)))))

(reg-event-db
 ::set-string-value
 [trim-v]
 (fn [db [article-id label-id value-idx label-value]]
   (update-label-value db article-id label-id
                       #(assoc (vec %) value-idx label-value))))

(reg-event-db
 ::extend-string-answer
 [trim-v]
 (fn [db [article-id label-id]]
   (update-label-value db article-id label-id
                       #(assoc (vec %) (count %) ""))))

;; Simulates an "enable value" label input component event
(reg-event-fx
 :review/trigger-enable-label-value
 [trim-v]
 (fn [{:keys [db]} [article-id label-id label-value]]
   (let [{:keys [value-type]} (get-label-raw db label-id)]
     (condp = value-type
       "boolean"
       {:db (set-label-value db article-id label-id label-value)}

       "categorical"
       {::select-categorical-value [article-id label-id label-value]}))))

(defn- review-article-loading? []
  (let [article-id @(subscribe [:review/editing-id])
        project-id @(subscribe [:active-project-id])]
    (when (and article-id project-id)
      (loading/item-loading? [:article project-id article-id]))))

;; Renders input component for label
(defmulti label-input-el
  (fn [label-id article-id] @(subscribe [:label/value-type label-id])))

(defmethod label-input-el "boolean"
  [label-id article-id]
  (let [answer (subscribe [:review/active-labels article-id label-id])]
    [ui/three-state-selection
     {:set-answer!
      #(dispatch [::set-label-value article-id label-id %])
      :value answer}]))

(defmethod label-input-el "categorical"
  [label-id article-id]
  (r/create-class
   {:component-did-mount
    (fn [c]
      (-> (js/$ (r/dom-node c))
          (.dropdown
           (clj->js
            {:onAdd
             (fn [v t]
               (dispatch [::add-label-value article-id label-id v]))
             :onRemove
             (fn [v t]
               (dispatch [::remove-label-value article-id label-id v]))
             :onChange
             (fn [_] (.dropdown (js/$ (r/dom-node c))
                                "hide"))}))))
    :component-will-update
    (fn [c]
      (let [answer @(subscribe [:review/active-labels article-id label-id])
            active-vals (->> answer (str/join ","))
            comp-vals (-> (js/$ (r/dom-node c))
                          (.dropdown "get value"))]
        (when (not= comp-vals active-vals)
          (-> (js/$ (r/dom-node c))
              (.dropdown "set exactly" active-vals)))))
    :reagent-render
    (fn [label-id article-id]
      (when (= article-id @(subscribe [:review/editing-id]))
        (let [required? @(subscribe [:label/required? label-id])
              all-values
              (as-> @(subscribe [:label/all-values label-id]) vs
                (if (every? string? vs)
                  (concat
                   (->> vs (filter #(in? ["none" "other"] (str/lower-case %))))
                   (->> vs (remove #(in? ["none" "other"] (str/lower-case %)))
                        (sort #(compare (str/lower-case %1)
                                        (str/lower-case %2)))))
                  vs))
              current-values @(subscribe [:review/active-labels article-id label-id])
              dom-id (str "label-edit-" article-id "-" label-id)
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
             :value (str/join "," current-values)
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
                  (str lval)]))))]])))}))

(defmethod label-input-el "string"
  [label-id article-id]
  (let [curvals (as-> @(subscribe [:review/active-labels article-id label-id])
                    vs
                    (if (empty? vs) [""] vs))
        multi? @(subscribe [:label/multi? label-id])
        nvals (count curvals)]
    (when (= article-id @(subscribe [:review/editing-id]))
      [:div.inner
       (doall
        (->>
         curvals
         (map-indexed
          (fn [i val]
            (let [left-action? true
                  right-action? (and multi? (= i (dec nvals)))
                  valid? @(subscribe [:label/valid-string-value? label-id val])]
              ^{:key [label-id i]}
              [:div.ui.form.string-label
               [:div.field.string-label
                {:class (cond (empty? val)  ""
                              valid?        "success"
                              :else         "error")}
                [:div.ui.fluid
                 {:class
                  (str
                   (if left-action? "labeled" "")
                   " " (if right-action? "right action input" "input")
                   " ")}
                 (when left-action?
                   [:div.ui.label.input-remove
                    [:div.ui.icon.button
                     {:class (if (and (= i 0)
                                      (= nvals 1)
                                      (empty? val)) "disabled" "")
                      :on-click
                      (fn [ev]
                        (dispatch [::remove-string-value
                                   article-id label-id i]))}
                     [:i.fitted.times.icon]]])
                 [:input
                  {:type "text"
                   :name (str label-id "__" i)
                   :value val
                   :on-change
                   (fn [ev]
                     (let [s (-> ev .-target .-value)]
                       (dispatch-sync [::set-string-value
                                       article-id label-id i s])))}]
                 (when right-action?
                   [:div.ui.icon.button.input-row
                    {:class (if (empty? val) "disabled" "")
                     :on-click
                     (fn [ev]
                       (dispatch [::extend-string-answer
                                  article-id label-id]))}
                    [:i.fitted.plus.icon]])]]])))))])))

(defn- inclusion-tag [article-id label-id]
  (let [criteria? @(subscribe [:label/inclusion-criteria? label-id])
        answer @(subscribe [:review/active-labels article-id label-id])
        inclusion @(subscribe [:label/answer-inclusion label-id answer])
        boolean-label? @(subscribe [:label/boolean? label-id])
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
                                              {:visibility "hidden"})}])))

(defn- label-help-popup [label]
  (when (or true (full-size?))
    (let [{:keys [category required question definition]} label
          criteria? (= category "inclusion criteria")
          required? required
          examples (:examples definition)]
      [:div.ui.inverted.grid.popup.transition.hidden.label-help
       [:div.middle.aligned.center.aligned.row.label-help-header
        [:div.ui.sixteen.wide.column
         [:span (cond (not criteria?)  "Extra label"
                      required?        "Inclusion criteria [Required]"
                      :else            "Inclusion criteria")]]]
       [:div.middle.aligned.center.aligned.row.label-help-question
        [:div.sixteen.wide.column.label-help
         [:div [:span (str question)]]
         (when (seq examples)
           [:div
            [:div.ui.small.divider]
            [:div
             [:strong "Examples: "]
             (doall
              (map-indexed
               (fn [i ex]
                 ^{:key i}
                 [:div.ui.small.green.label (str ex)])
               examples))]])]]])))

(reg-sub
 ::label-css-class
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
(defn- label-column [article-id label-id row-position]
  (let [value-type @(subscribe [:label/value-type label-id])
        label-css-class @(subscribe [::label-css-class article-id label-id])
        label-string @(subscribe [:label/display label-id])
        question @(subscribe [:label/question label-id])]
    ^{:key {:article-label [article-id label-id]}}
    [:div.ui.column.label-edit {:class label-css-class}
     [:div.ui.middle.aligned.grid.label-edit
      [ui/with-tooltip
       (let [name-content
             [:span.name
              {:class (when (>= (count label-string) 30)
                        "small-text")}
              [:span.inner label-string]]]
         (if (and (mobile?) (>= (count label-string) 30))
           [:div.ui.row.label-edit-name
            [inclusion-tag article-id label-id]
            [:span.name " "]
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])
            [:div.clear name-content]]
           [:div.ui.row.label-edit-name
            [inclusion-tag article-id label-id]
            name-content
            (when (not-empty question)
              [:i.right.floated.fitted.grey.circle.question.mark.icon])]))
       {:variation "basic"
        :delay {:show 400, :hide 0}
        :hoverable false
        :inline true
        :position (cond
                    (= row-position :left)
                    "top left"
                    (= row-position :right)
                    "top right"
                    :else
                    "top center")
        :distanceAway 8}]
      [label-help-popup {:category @(subscribe [:label/category label-id])
                         :required @(subscribe [:label/required? label-id])
                         :question @(subscribe [:label/question label-id])
                         :definition {:examples @(subscribe [:label/examples label-id])}}]
      [:div.ui.row.label-edit-value
       {:class (case value-type
                 "boolean"      "boolean"
                 "categorical"  "category"
                 "string"       "string"
                 "")}
       [:div.inner
        [label-input-el label-id article-id]]]]]))

(defn- note-input-element [note-name]
  (when @(subscribe [:project/notes nil note-name])
    (let [article-id @(subscribe [:review/editing-id])
          user-id @(subscribe [:self/user-id])
          note-description @(subscribe [:note/description note-name])
          note-content @(subscribe [:review/active-note article-id note-name])]
      [:div.ui.attached.segment.notes
       [:div.ui.middle.aligned.form.notes
        [:div.middle.aligned.field.notes
         [:label.middle.aligned.notes
          note-description
          [:i.large.middle.aligned.icon
           {:class "grey write"}]]
         [:textarea
          {:type "text"
           :rows 2
           :name note-name
           :value (or note-content "")
           :on-change
           #(let [content (-> % .-target .-value)]
              (dispatch-sync [:review/set-note-content
                              article-id note-name content]))}]]]])))

(defn- activity-report []
  (when-let [today-count @(subscribe [:review/today-count])]
    (if (full-size?)
      [:div.ui.large.label.activity-report
       [:span.ui.green.circular.label today-count]
       [:span nbsp "finished today"]]
      [:div.ui.large.label.activity-report
       [:span.ui.tiny.green.circular.label today-count]
       [:span nbsp "today"]])))

;; Component for row of action buttons below label inputs grid
(defn- label-editor-buttons-view [article-id]
  (let [project-id @(subscribe [:active-project-id])
        active-labels @(subscribe [:review/active-labels article-id])
        resolving? @(subscribe [:review/resolving?])
        missing @(subscribe [:review/missing-labels article-id])
        disabled? (not-empty missing)
        saving? (and @(subscribe [:review/saving? article-id])
                     (or (loading/any-action-running? :only :review/send-labels)
                         (loading/any-loading? :only :article)
                         (loading/any-loading? :only :review/task)))
        loading-task? (and (not saving?)
                           @(subscribe [:review/on-review-task?])
                           (loading/item-loading? [:review/task project-id]))
        on-review-task? @(subscribe [:review/on-review-task?])
        review-task-id @(subscribe [:review/task-id])
        on-save
        (fn []
          (notes/sync-article-notes article-id)
          (dispatch
           [:review/send-labels
            {:project-id project-id
             :article-id article-id
             :confirm? true
             :resolve? (boolean resolving?)
             :on-success
             (->> (list (when (or on-review-task? (= article-id review-task-id))
                          [:fetch [:review/task project-id]])
                        (when (not on-review-task?)
                          [:fetch [:article project-id article-id]])
                        (when (not on-review-task?)
                          [:review/disable-change-labels article-id])
                        (when @(subscribe [:user-labels/article-id])
                          ;; Use setTimeout here to avoid immediately triggering
                          ;; the :review/send-labels logic in sr-defroute before
                          ;; state has been fully updated
                          #(js/setTimeout
                            (fn [] (nav-scroll-top (project-uri project-id "/user")))
                            50)))
                  (remove nil?))}]))
        save-class (str (if disabled? "disabled" "")
                        " "
                        (if saving? "loading" "")
                        " "
                        (if resolving? "purple button" "primary button"))
        on-next #(when on-review-task?
                   (notes/sync-article-notes article-id)
                   (dispatch [:review/send-labels
                              {:project-id project-id
                               :article-id article-id
                               :confirm? false
                               :resolve? false}])
                   (dispatch [:fetch [:review/task project-id]]))]
    [:div.ui.bottom.attached.segment
     (if (full-size?)
       [:div.ui.center.aligned.middle.aligned.grid.label-editor-buttons-view
        [ui/CenteredColumn
         (when on-review-task?
           [activity-report])
         "left aligned four wide column"]
        [ui/CenteredColumn
         [:div.ui.grid.centered
          [:div.ui.row
           (let [save-button
                 [:div.ui.right.labeled.icon
                  {:class save-class
                   :on-click on-save}
                  (if resolving? "Resolve Labels" "Save Labels")
                  [:i.check.circle.outline.icon]]]
             (if disabled?
               [ui/with-tooltip [:div save-button]]
               save-button))
           [:div.ui.inverted.popup.top.left.transition.hidden
            "Answer missing for a required label"]
           (when on-review-task?
             [:div.ui.right.labeled.icon.button
              {:class (if loading-task? "loading" "")
               :on-click on-next}
              "Skip Article"
              [:i.right.circle.arrow.icon]])]]
         "center aligned eight wide column"]
        [ui/CenteredColumn
         [:span]
         "right aligned four wide column"]]
       [:div.ui.center.aligned.middle.aligned.grid.label-editor-buttons-view
        [ui/CenteredColumn
         (when on-review-task?
           [activity-report])
         "left aligned four wide column"]
        [ui/CenteredColumn
         [:div.ui.center.aligned.grid
          [:div.ui.row
           (let [save-button
                 [:div.ui.small
                  {:class save-class
                   :on-click on-save}
                  (if resolving? "Resolve" "Save")
                  [:i.right.check.circle.outline.icon]]]
             (if disabled?
               [ui/with-tooltip [:div save-button]]
               save-button))
           [:div.ui.inverted.popup.top.left.transition.hidden
            "Answer missing for a required label"]
           (when on-review-task?
             [:div.ui.small.button
              {:class (if loading-task? "loading" "")
               :on-click on-next}
              "Skip"
              [:i.right.circle.arrow.icon]])]]
         "center aligned eight wide column"]
        [ui/CenteredColumn
         [:span]
         "right aligned four wide column"]])]))

;; Top-level component for label editor
(defn label-editor-view [article-id]
  (when article-id
    (when-let [project-id @(subscribe [:active-project-id])]
      (with-loader [[:article project-id article-id]] {}
        (if (not= article-id @(subscribe [:review/editing-id]))
          [:div]
          (let [change-set? @(subscribe [:review/change-labels? article-id])
                label-ids @(subscribe [:project/label-ids])
                resolving? @(subscribe [:review/resolving?])
                n-cols (cond (full-size?) 4 (mobile?) 2 :else 3)
                n-cols-str (case n-cols 4 "four" 2 "two" 3 "three")
                make-label-columns
                (fn [label-ids n-cols]
                  (doall
                   (for [row (partition-all n-cols label-ids)]
                     ^{:key [(first row)]}
                     [:div.row
                      (doall
                       (concat
                        (map-indexed
                         (fn [i label-id]
                           (label-column
                            article-id label-id
                            (cond (= i 0) :left
                                  (= i (dec n-cols)) :right
                                  :else :middle)))
                         row)
                        (when (< (count row) n-cols)
                          [^{:key {:label-row-end (last row)}}
                           [:div.column]])))])))]
            [:div.ui.segments.label-editor-view
             [:div.ui.top.attached.segment
              [:div.ui.two.column.middle.aligned.grid
               [:div.ui.left.aligned.column
                [:h3 (if resolving? "Resolve Labels" "Set Labels")]]
               [:div.ui.right.aligned.column
                [:a.ui.tiny.right.labeled.icon.button
                 {:href (project-uri project-id "/labels/edit")
                  :on-click
                  (wrap-prevent-default
                   (fn [_]
                     (nav-scroll-top (project-uri project-id "/labels/edit"))))}
                 [:i.sliders.horizontal.icon]
                 "Definitions"]
                (when change-set?
                  [:div.ui.tiny.button
                   {:on-click #(dispatch [:review/disable-change-labels article-id])}
                   "Cancel"])]]]
             [:div.ui.label-section
              {:class (str "attached "
                           n-cols-str " column "
                           "celled grid segment")}
              (make-label-columns label-ids n-cols)]
             [note-input-element "default"]
             [label-editor-buttons-view article-id]]))))))
