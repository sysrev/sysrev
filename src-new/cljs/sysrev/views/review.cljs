(ns sysrev.views.review
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync
     reg-event-db reg-event-fx reg-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [sysrev.views.components :refer [with-tooltip three-state-selection]]
   [sysrev.subs.review :as review]
   [sysrev.subs.labels :as labels]
   [sysrev.subs.articles :as articles]
   [sysrev.util :refer [full-size? mobile?]]
   [sysrev.shared.util :refer [in?]]))

(defn- set-label-value [db article-id label-id label-value]
  (assoc-in db [:state :review :labels article-id label-id]
            label-value))

(defn- update-label-value [db article-id label-id update-fn]
  (update-in db [:state :review :labels article-id label-id]
             update-fn))

(reg-event-fx
 ::send-labels
 [trim-v]
 (fn [{:keys [db]} [force? args]]
   (when-let [article-id (:article-id args)]
     (let [label-values (review/active-labels db article-id)
           confirmed? (= (articles/article-user-status db article-id)
                         :confirmed)]
       (when (or (not confirmed?) force?)
         {:dispatch-later [{:ms 50 :dispatch [:review/send-labels args]}]})))))

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
   (update-label-value db article-id label-id
                       #(-> % vec (conj label-value) distinct))))

;; Removes a value from an active label answer vector
(reg-event-db
 ::remove-label-value
 [trim-v]
 (fn [db [article-id label-id label-value]]
   (update-label-value db article-id label-id
                       #(->> % (remove (partial = label-value)) vec))))

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
   (let [{:keys [value-type]} (labels/get-label-raw db label-id)]
     (condp = value-type
       "boolean"
       {:db (set-label-value db article-id label-id label-value)
        :dispatch [::send-labels false {:article-id article-id}]}

       "categorical"
       {::select-categorical-value [article-id label-id label-value]}))))

(defn category-label-input [article-id label-id]
  (r/create-class
   {:component-did-mount
    (fn [c]
      (.dropdown
       (js/$ (r/dom-node c))
       (clj->js
        {:onAdd
         (fn [v t]
           (dispatch [::add-label-value article-id label-id v])
           (dispatch [::send-labels false {:article-id article-id}]))
         :onRemove
         (fn [v t]
           (dispatch [::remove-label-value article-id label-id v])
           (dispatch [::send-labels false {:article-id article-id}]))
         :onChange
         (fn [_] (.dropdown (js/$ (r/dom-node c))
                            "hide"))})))
    :component-will-update
    (fn [c]
      (let [db @app-db
            active-vals (->> (get (review/active-labels db article-id) label-id)
                             (str/join ","))
            comp-vals (-> (js/$ (r/dom-node c))
                          (.dropdown "get value"))]
        (when (not= comp-vals active-vals)
          (-> (js/$ (r/dom-node c))
              (.dropdown "set exactly" active-vals)))))
    :reagent-render
    (fn [article-id label-id]
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
            dom-id (str "label-edit-" article-id "-" label-id)]
        [:div.ui.large.fluid.multiple.selection.search.dropdown
         {:id dom-id
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
           (for [lval all-values]
             ^{:key [label-id lval]}
             [:div.item {:data-value (str lval)}
              (str lval)]))]]))}))

(defn string-label-input [article-id label-id]
  (let [curvals (as-> @(subscribe [:review/active-labels article-id label-id]) vs
                  (if (empty? vs) [""] vs))
        multi? @(subscribe [:label/multi? label-id])
        nvals (count curvals)]
    [:div.inner {:style {:width "100%"}}
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
              [:div.ui.fluid.small
               {:class
                (str
                 (if left-action? "labeled" "")
                 " " (if right-action? "right action input" "input")
                 " ")}
               (when left-action?
                 [:div.ui.label.input-remove
                  [:div.ui.button
                   {:class (if (and (= i 0)
                                    (= nvals 1)
                                    (empty? val)) "disabled" "")
                    :on-click
                    (fn [ev]
                      (dispatch [::remove-string-value
                                 article-id label-id i]))}
                   [:i.fitted.small.remove.icon]]])
               [:input
                {:type "text"
                 :name (str label-id "__" i)
                 :value val
                 :on-change
                 (fn [ev]
                   (let [s (-> ev .-target .-value)]
                     (dispatch [::set-string-value
                                article-id label-id i s])))}]
               (when right-action?
                 [:div.ui.icon.button.input-row
                  {:class (if (empty? val) "disabled" "")
                   :on-click
                   (fn [ev]
                     (dispatch [::extend-string-answer
                                article-id label-id]))}
                  [:i.fitted.small.plus.icon]])]]])))))]))

(defn- inclusion-tag [label-id answer]
  (if @(subscribe [:label/inclusion-criteria? label-id])
    (let [inclusion @(subscribe [:label/answer-inclusion label-id answer])
          color (case inclusion
                  true   "green"
                  false  "orange"
                  nil    "grey")
          iclass (case inclusion
                   true   "circle plus icon"
                   false  "circle minus icon"
                   nil    "circle outline icon")]
      [:i.left.floated.fitted {:class (str color " " iclass)}])
    [:i.left.floated.fitted {:class "grey content icon"}]))

(defn- label-help-popup [label-id]
  (when (full-size?)
    (let [criteria? @(subscribe [:label/inclusion-criteria? label-id])
          required? @(subscribe [:label/required? label-id])
          question @(subscribe [:label/question label-id])
          examples @(subscribe [:label/examples label-id])]
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

(defmulti label-column
  (fn [label-id] @(subscribe [:label/value-type label-id])))

(defmethod label-column "boolean"
  [label-id]
  (let [required? @(subscribe [:label/required? label-id])
        criteria? @(subscribe [:label/inclusion-criteria? label-id])
        article-id @(subscribe [:review/editing-id])
        answer @(subscribe [:review/active-labels article-id label-id])]
    [:div.ui.column.label-edit
     {:class (cond required?       "required"
                   (not criteria?) "extra"
                   :else           "")}
     [:div.ui.middle.aligned.grid.label-edit
      [with-tooltip
       [:div.ui.row.label-edit-name
        [inclusion-tag label-id answer]
        [:span.name
         [:span.inner
          (str @(subscribe [:label/display label-id]) "?")]]]
       {:delay {:show 400
                :hide 0}
        :hoverable false
        :transition "fade up"
        :distanceAway 8
        :variation "basic"}]
      [label-help-popup label-id]
      [:div.ui.row.label-edit-value.boolean
       [:div.inner
        [three-state-selection
         (fn [new-value]
           (dispatch [::set-label-value article-id label-id new-value])
           (dispatch [::send-labels false {:article-id article-id}]))
         answer]]]]]))

(defmethod label-column "categorical"
  [label-id]
  (let [required? @(subscribe [:label/required? label-id])
        article-id @(subscribe [:review/editing-id])
        answer @(subscribe [:review/active-labels article-id label-id])]
    [:div.ui.column.label-edit
     {:class (if required? "required" nil)}
     [:div.ui.middle.aligned.grid.label-edit
      [with-tooltip
       [:div.ui.row.label-edit-name
        [inclusion-tag label-id answer]
        [:span.name
         [:span.inner
          (str @(subscribe [:label/display label-id]))]]]
       {:delay {:show 400
                :hide 0}
        :hoverable false
        :transition "fade up"
        :distanceAway 8
        :variation "basic"}]
      [label-help-popup label-id]
      [:div.ui.row.label-edit-value.category
       [:div.inner [category-label-input article-id label-id]]]]]))

(defmethod label-column "string"
  [label-id]
  (let [required? @(subscribe [:label/required? label-id])
        article-id @(subscribe [:review/editing-id])
        answer @(subscribe [:review/active-labels article-id label-id])]
    [:div.ui.column.label-edit
     {:class (if required? "required" nil)}
     [:div.ui.middle.aligned.grid.label-edit
      [with-tooltip
       [:div.ui.row.label-edit-name
        [:span.name
         [:span.inner
          (str @(subscribe [:label/display label-id]))]]]
       {:delay {:show 400
                :hide 0}
        :hoverable false
        :transition "fade up"
        :distanceAway 8
        :variation "basic"}]
      [label-help-popup label-id]
      [:div.ui.row.label-edit-value.string
       [string-label-input article-id label-id]]]]))

(defn label-editor-view []
  (let [label-ids @(subscribe [:project/label-ids])
        resolving? @(subscribe [:review/resolving?])
        n-cols (cond (full-size?) 4 (mobile?) 2 :else 3)
        n-cols-str (case n-cols 4 "four" 2 "two" 3 "three")
        make-rows
        (fn [label-ids n-cols]
          (doall
           (for [row (partition-all n-cols label-ids)]
             ^{:key [(first row)]}
             [:div.row
              (doall
               (concat
                (map (fn [label-id]
                       ^{:key label-id}
                       [label-column label-id])
                     row)
                (when (< (count row) n-cols)
                  [^{:key {:label-row-end (last row)}}
                   [:div.column]])))])))]
    [:div
     [:div.ui.top.attached.header.segment
      [:h3
       (if resolving? "Resolve labels " "Edit labels ")
       [with-tooltip
        [:a {:href "/project/labels"}
         [:i.medium.grey.help.circle.icon]]]
       [:div.ui.inverted.popup.top.left.transition.hidden
        "View label definitions"]]]
     [:div.ui.label-section
      {:class (str "attached "
                   n-cols-str " column "
                   "celled grid segment")}
      (make-rows label-ids n-cols)]
     #_ [inconsistent-answers-notice label-values]
     #_ [note-input-element article-id]]))
