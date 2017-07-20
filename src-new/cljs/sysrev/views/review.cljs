(ns sysrev.views.review
  (:require
   [clojure.spec.alpha :as s]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync]]
   [sysrev.views.components :refer
    [with-tooltip three-state-selection]]
   [sysrev.util :refer [full-size?]]))

(defn- inclusion-tag [label-id answer]
  (if @(subscribe [:label/inclusion-criteria?])
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
        answer @(subscribe [:review/label-answer article-id label-id])]
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
           (dispatch-sync [:review/set-label-value
                           article-id label-id new-value])
           (dispatch [:review/send-labels]))
         answer]]]]]))
