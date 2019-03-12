(ns sysrev.views.labels
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer [subscribe dispatch]]
   [cljs-time.core :as t]
   [sysrev.views.components :refer [updated-time-label note-content-label]]
   [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
   [sysrev.state.labels :refer [real-answer?]]
   [sysrev.util :refer [time-from-epoch time-elapsed-string]]
   [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn label-answer-tag
  "UI component for displaying a label answer."
  [label-id answer]
  (let [display @(subscribe [:label/display label-id])
        value-type @(subscribe [:label/value-type label-id])
        category @(subscribe [:label/category label-id])
        inclusion @(subscribe [:label/answer-inclusion label-id answer])
        color (case inclusion
                true   "green"
                false  "orange"
                nil)
        values (if @(subscribe [:label/boolean? label-id])
                 (if (boolean? answer)
                   [answer] [])
                 (cond (nil? answer)        nil
                       (sequential? answer) answer
                       :else                [answer]))
        display-label (if @(subscribe [:label/boolean? label-id])
                        (str display "?")
                        display)
        dark-theme? @(subscribe [:self/dark-theme?])]
    [:div.ui.tiny.labeled.button.label-answer-tag
     [:div.ui.button {:class (when dark-theme? "basic")}
      (str display-label " ")]
     [:div.ui.basic.label
      [:span {:class (when color (str color "-text"))}
       (if (empty? values)
         [:i.grey.question.circle.icon
          {:style {:margin-right "0"}
           :aria-hidden true}]
         (str/join ", " values))]]]))

(defn label-values-component [labels & {:keys [notes user-name resolved?]}]
  (let [dark-theme? @(subscribe [:self/dark-theme?])
        note-entries (concat
                      (for [note-name (keys notes)] ^{:key [note-name]}
                        [note-content-label note-name (get notes note-name)]))
        label-entries (->> @(subscribe [:project/label-ids])
                           (filter #(contains? labels %))
                           (map #(do [% (get-in labels [% :answer])])))]
    [:div.label-values
     (when user-name
       [:div.ui.label.user-name
        {:class (if dark-theme? nil "basic")}
        user-name])
     (doall
      (->> label-entries
           (map-indexed
            (fn [i [label-id answer]]
              (when (real-answer? answer) ^{:key i}
                [label-answer-tag label-id answer])))))
     (when (and (some #(contains? % :confirm-time) (vals labels))
                (some #(in? [0 nil] (:confirm-time %)) (vals labels)))
       [:div.ui.basic.yellow.label.labels-status
        "Unconfirmed"])
     (when resolved?
       [:div.ui.basic.purple.label.labels-status
        "Resolved"])
     (doall note-entries)
     #_
     (when (not-empty note-entries)
       (if (empty? label-entries)
         (doall note-entries)
         [:div {:style {:margin-left "-3px"
                        :margin-bottom "3px"}}
          (doall note-entries)]))]))

(defn article-label-values-component [article-id user-id]
  (let [labels @(subscribe [:article/labels article-id user-id])
        resolved? (= user-id @(subscribe [:article/resolve-user-id article-id]))]
    [label-values-component labels :resolved? resolved?]))

(defn article-labels-view [article-id &
                           {:keys [self-only?] :or {self-only? false}}]
  (let [project-id @(subscribe [:active-project-id])
        self-id @(subscribe [:self/user-id])
        user-labels @(subscribe [:article/labels article-id])
        user-ids (sort (keys user-labels))
        label-ids
        (->> (vals user-labels)
             (map (fn [ulmap]
                    (->> (keys ulmap)
                         (filter #(real-answer?
                                   (get-in ulmap [% :answer]))))))
             (apply concat)
             distinct)
        user-confirmed?
        (fn [user-id]
          (let [ulmap (get user-labels user-id)]
            (every? #(true? (get-in ulmap [% :confirmed]))
                    (keys ulmap))))
        some-real-answer?
        (fn [user-id]
          (let [ulmap (get user-labels user-id)]
            (some #(real-answer? (get-in ulmap [% :answer]))
                  (keys ulmap))))
        resolved?
        (fn [user-id] (= user-id @(subscribe [:article/resolve-user-id article-id])))
        user-ids-resolved
        (->> user-ids
             (filter resolved?)
             (filter some-real-answer?)
             (filter user-confirmed?))
        user-ids-other
        (->> user-ids
             (remove resolved?)
             (filter some-real-answer?)
             (filter user-confirmed?))
        user-ids-ordered
        (cond->> (concat user-ids-resolved user-ids-other)
          self-only? (filter (partial = self-id)))]
    (when (seq user-ids-ordered)
      (with-loader [[:article project-id article-id]]
        {:class "ui segments article-labels-view"}
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
             [:div.ui.segment {:key user-id}
              [:h5.ui.dividing.header
               [:div.ui.two.column.middle.aligned.grid
                [:div.row
                 [:div.column (if self-only?
                                "Your Labels"
                                [:div
                                 [Avatar {:user-id user-id}]
                                 [UserPublicProfileLink {:user-id user-id
                                                         :display-name user-name}]])]
                 [:div.right.aligned.column
                  [updated-time-label updated-time]]]]]
              [:div.labels
               [article-label-values-component article-id user-id]]
              (let [note-content
                    @(subscribe [:article/notes article-id user-id "default"])]
                (when (and (string? note-content)
                           (not-empty (str/trim note-content)))
                  [:div.notes
                   [note-content-label "default" note-content]]))])))))))
