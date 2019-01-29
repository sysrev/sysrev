(ns sysrev.state.review
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-sub-raw
              reg-event-db reg-event-fx trim-v reg-fx]]
            [reagent.ratom :refer [reaction]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [active-panel]]
            [sysrev.state.articles :as articles]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.shared.util :refer [in? map-values]]))

(defn review-task-id [db]
  (get-in db [:data :review :task-id]))

(reg-sub :review/task-id review-task-id)

(defn- load-review-task [db article-id today-count]
  (-> db
      (assoc-in [:data :review :task-id] article-id)
      (assoc-in [:data :review :today-count] today-count)))

(def-data :review/task
  :loaded? review-task-id
  :uri (fn [project-id] "/api/label-task")
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :content (fn [project-id] {:project-id project-id})
  :process
  (fn [{:keys [db]}
       [project-id]
       {:keys [article labels notes today-count] :as result}]
    (if (= result :none)
      {:db (-> db
               (load-review-task :none nil))}
      (let [article (merge article {:labels labels :notes notes})]
        (cond->
           {:db (-> db
                    (load-review-task (:article-id article) today-count)
                    (articles/load-article article))}
           (= (active-panel db) [:project :review])
           (merge {:scroll-top true}))))))

(def-action :review/send-labels
  :uri (fn [project-id _] "/api/set-labels")
  :content (fn [project-id {:keys [article-id label-values
                                   change? confirm? resolve?]}]
             {:project-id project-id
              :article-id article-id
              :label-values label-values
              :confirm? (boolean confirm?)
              :resolve? (boolean resolve?)
              :change? (boolean change?)})
  :process
  (fn [_ [project-id {:keys [on-success]}] result]
    (when on-success
      (let [success-fns (filter fn? on-success)
            success-events (remove fn? on-success)]
        (doseq [f success-fns] (f))
        {:dispatch-n success-events}))))

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
 :<- [:project-articles/editing?]
 :<- [:project-articles/article-id]
 (fn [[on-review-task? task-aid
       project-editing? project-aid]]
   (cond (and on-review-task?
              (integer? task-aid))  task-aid
         project-editing?           project-aid)))

(reg-sub
 :review/editing?
 :<- [:review/editing-id]
 (fn [editing-id]
   (if editing-id true false)))

(reg-sub
 :review/resolving?
 :<- [:project-articles/article-id]
 :<- [:project-articles/resolving?]
 (fn [[project-aid project-resolving?]]
   (boolean (and project-aid project-resolving?))))

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
