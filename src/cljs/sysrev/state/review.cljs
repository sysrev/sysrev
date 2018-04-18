(ns sysrev.state.review
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-sub-raw
              reg-event-db reg-event-fx trim-v reg-fx]]
            [reagent.ratom :refer [reaction]]
            [sysrev.state.nav :refer [active-panel]]
            [sysrev.state.articles :as articles]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.shared.util :refer [in? map-values]]))

(defn review-task-id [db]
  (get-in db [:data :review :task-id]))

(reg-sub :review/task-id review-task-id)

(reg-sub
 :review/today-count
 (fn [db]
   (get-in db [:data :review :today-count])))

(reg-sub
 :review/on-review-task?
 :<- [:active-panel]
 (fn [panel]
   (= panel [:project :review])))

(reg-sub
 :review/editing-id
 :<- [:review/on-review-task?]
 :<- [:review/task-id]
 :<- [:public-labels/editing?]
 :<- [:public-labels/article-id]
 :<- [:user-labels/editing?]
 :<- [:user-labels/article-id]
 (fn [[on-review-task? task-aid public-editing? public-aid user-editing? user-aid]]
   (cond (and on-review-task?
              (integer? task-aid))  task-aid
         public-editing?            public-aid
         user-editing?              user-aid)))

(reg-sub
 :review/editing?
 :<- [:review/editing-id]
 (fn [editing-id]
   (if editing-id true false)))

(reg-sub
 :review/resolving?
 :<- [:public-labels/article-id]
 :<- [:public-labels/resolving?]
 (fn [[public-aid public-resolving?]]
   (boolean
    (and public-aid public-resolving?))))

(reg-sub
 ::labels
 (fn [db]
   (get-in db [:state :review :labels])))

(reg-sub
 :review/saving?
 (fn [[_ article-id]]
   [(subscribe [:panel-field [:saving-labels article-id]])])
 (fn [[saving?]] saving?))

(reg-sub
 :review/change-labels?
 (fn [[_ article-id panel]]
   [(subscribe [:panel-field [:transient :change-labels? article-id] panel])])
 (fn [[change-labels?]] change-labels?))

(defn review-ui-labels [db article-id]
  (get-in db [:state :review :labels article-id]))

(defn active-labels [db article-id]
  (when-let [user-id (current-user-id db)]
    (merge (->> (articles/article-labels db article-id user-id)
                (map-values :answer))
           (review-ui-labels db article-id))))

(reg-sub
 :review/active-labels
 (fn [[_ article-id label-id]]
   [(subscribe [:self/user-id])
    (subscribe [::labels])
    (subscribe [:article/labels article-id])])
 (fn [[user-id ui-labels article-labels] [_ article-id label-id]]
   (let [ui-vals (get-in ui-labels [article-id] {})
         article-vals (->> (get-in article-labels [user-id] {})
                           (map-values :answer))]
     (cond-> (merge article-vals ui-vals)
       label-id (get label-id)))))

(reg-sub-raw
 :review/inconsistent-labels
 (fn [db [_ article-id label-id]]
   (reaction
    (let [label-ids @(subscribe [:project/label-ids])
          values @(subscribe [:review/active-labels article-id])
          overall-id @(subscribe [:project/overall-label-id])
          overall-val (some->> overall-id (get values))
          inconsistent
          (when (true? overall-val)
            (->> label-ids
                 (filter
                  (fn [label-id]
                    (let [label-val (get values label-id)
                          inclusion @(subscribe [:label/answer-inclusion
                                                 label-id label-val])]
                      (false? inclusion))))
                 (#(if (empty? %) % (conj % overall-id)))))]
      (if label-id
        (boolean (in? inconsistent label-id))
        (vec inconsistent))))))

(reg-sub-raw
 :review/missing-labels
 (fn [_ [_ article-id]]
   (reaction
    (let [active-labels @(subscribe [:review/active-labels article-id])
          required-ids (->> @(subscribe [:project/label-ids])
                            (filter #(deref (subscribe [:label/required? %]))))
          have-answer? (fn [label-id]
                         @(subscribe [:label/non-empty-answer?
                                      label-id (get active-labels label-id)]))]
      (->> required-ids (remove have-answer?) vec)))))

;; Update review interface state based response from task request
(reg-event-db
 :review/load-task
 [trim-v]
 (fn [db [article-id today-count]]
   (-> db
       (assoc-in [:data :review :task-id] article-id)
       (assoc-in [:data :review :today-count] today-count))))

;; Record POST action to send labels having been initiated,
;; to show loading indicator on the button that was clicked.
(reg-event-fx
 :review/mark-saving
 [trim-v]
 (fn [_ [article-id panel]]
   {:dispatch [:set-panel-field [:saving-labels article-id] true panel]}))

;; Reset state set by :review/mark-saving
(reg-event-fx
 :review/reset-saving
 [trim-v]
 (fn [_ [article-id panel]]
   (let [field (if article-id [:saving-labels article-id] [:saving-labels])]
     {:dispatch [:set-panel-field field nil panel]})))

;; Change interface state to enable label editor for an article where
;; user has confirmed answers.
(reg-event-fx
 :review/enable-change-labels
 [trim-v]
 (fn [_ [article-id panel]]
   {:dispatch [:set-panel-field [:transient :change-labels? article-id] true panel]}))

;; Hide label editor for article where user has confirmed answers
(reg-event-fx
 :review/disable-change-labels
 [trim-v]
 (fn [_ [article-id panel]]
   {:dispatch-n
    (list [:set-panel-field [:transient :change-labels? article-id] false panel]
          [:review/reset-ui-labels]
          [:review/reset-ui-notes])}))

;; Runs the :review/send-labels POST action using label values
;; taken from active review interface.
(reg-event-fx
 :review/send-labels
 [trim-v]
 (fn [{:keys [db]} [{:keys [project-id article-id confirm? resolve? on-success]}]]
   (let [label-values (active-labels db article-id)
         change? (= (articles/article-user-status db article-id)
                    :confirmed)
         panel (active-panel db)]
     {:dispatch-n
      (doall
       (->> (list (when confirm? [:review/mark-saving article-id panel])
                  [:action
                   [:review/send-labels
                    project-id
                    {:article-id article-id
                     :label-values label-values
                     :confirm? confirm?
                     :resolve? resolve?
                     :change? change?
                     :on-success on-success}]])
            (remove nil?)))})))

;; Reset state of locally changed label values in review interface
(reg-event-db
 :review/reset-ui-labels
 (fn [db]
   (assoc-in db [:state :review :labels] {})))
