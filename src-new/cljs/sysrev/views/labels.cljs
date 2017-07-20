(ns sysrev.views.labels
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer [subscribe dispatch]]
   [cljs-time.core :as t]
   [sysrev.views.components :refer [updated-time-label]]
   [sysrev.subs.labels :refer [real-answer?]]
   [sysrev.util :refer [time-from-epoch time-elapsed-string]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn label-answer-tag
  "UI component for displaying a label answer."
  [label-id answer]
  (let [display @(subscribe [:label/display label-id])
        value-type @(subscribe [:label/value-type label-id])
        category @(subscribe [:label/category])
        inclusion @(subscribe [:label/answer-inclusion label-id answer])
        color (case inclusion
                true "green"
                false "orange"
                nil "")
        values (case value-type
                 "boolean" (if (boolean? answer)
                             [answer] [])
                 "categorical" answer
                 "numeric" answer
                 "string" answer)
        display-label (case value-type
                        "boolean" (str display "?")
                        display)]
    [:div.ui.tiny.labeled.button.label-answer-tag
     [:div.ui.button {:class color}
      (str display-label " ")]
     [:div.ui.basic.label
      (if (empty? values)
        [:i.grey.help.circle.icon
         {:style {:margin-right "0"}
          :aria-hidden true}]
        (str/join ", " values))]]))

(defn label-values-component [article-id user-id]
  (let [label-ids @(subscribe [:project/label-ids])
        values @(subscribe [:article/labels article-id user-id])]
    [:div
     (doall
      (->>
       label-ids
       (map #(do [% (get-in values [% :answer])]))
       (map-indexed
        (fn [i [label-id answer]]
          (when (real-answer? answer)
            ^{:key [i article-id]}
            [label-answer-tag label-id answer])))))]))

(defn article-labels-view [article-id]
  (let [user-labels @(subscribe [:article/labels article-id])
        user-ids (sort (keys user-labels))
        label-ids
        (->> (vals user-labels)
             (map (fn [ulmap]
                    (->> (keys ulmap)
                         (filter #(real-answer?
                                   (get-in ulmap [% :answer]))))))
             (apply concat)
             distinct)
        some-real-answer? (fn [user-id]
                            (let [ulmap (get user-labels user-id)]
                              (some #(real-answer?
                                      (get-in ulmap [% :answer]))
                                    (keys ulmap))))
        resolved? (fn [user-id]
                    @(subscribe [:article/user-resolved? article-id user-id]))
        user-ids-resolved
        (->> user-ids
             (filter resolved?)
             (filter some-real-answer?))
        user-ids-other
        (->> user-ids
             (remove resolved?)
             (filter some-real-answer?))
        user-ids-ordered (concat user-ids-resolved user-ids-other)]
    (when (seq user-ids-ordered)
      [:div.ui.segments.article-labels-view
       (doall
        (for [user-id user-ids-ordered]
          (let [user-name @(subscribe [:user/display user-id])
                all-times (->> (vals (get user-labels user-id))
                               (map :confirm-epoch)
                               (remove nil?)
                               (remove zero?))
                updated-time (if (empty? all-times)
                               (t/now)
                               (time-from-epoch (apply max all-times)))]
            [:div.ui.attached.segment {:key user-id}
             [:h5.ui.dividing.header
              [:div.ui.two.column.middle.aligned.grid
               [:div.row
                [:div.column
                 user-name
                 (when (resolved? user-id)
                   [:div.ui.tiny.basic.purple.label "Resolved"])]
                [:div.right.aligned.column
                 [updated-time-label updated-time]]]]]
             [label-values-component article-id user-id]
             ;; TODO: Finish implementing notes to display here
             #_
             (when-let [unote (notes/get-note-field article-id user-id "default")]
               [:div.notes
                [:div.ui.divider]
                [:div.ui.tiny.labeled.button.user-note
                 [:div.ui.button "Notes"]
                 [:div.ui.basic.label {:style {:text-align "justify"}}
                  (:active unote)]]])])))])))
