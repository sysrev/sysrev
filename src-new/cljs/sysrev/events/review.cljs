(ns sysrev.events.review
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v reg-fx]]
   [sysrev.subs.ui :as ui]
   [sysrev.subs.auth :as auth]
   [sysrev.subs.labels :as labels]
   [sysrev.subs.review :as review]
   [sysrev.subs.articles :as articles]))

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
   {:dispatch [:set-panel-field [:change-labels? article-id] true panel]}))

;; Hide label editor for article where user has confirmed answers
(reg-event-fx
 :review/disable-change-labels
 [trim-v]
 (fn [_ [article-id panel]]
   {:dispatch [:set-panel-field [:change-labels? article-id] false panel]}))

;; Runs the :review/send-labels POST action using label values
;; taken from active review interface.
(reg-event-fx
 :review/send-labels
 [trim-v]
 (fn [{:keys [db]} [{:keys [article-id confirm? resolve? on-success]}]]
   (let [label-values (review/active-labels db article-id)
         change? (= (articles/article-user-status db article-id)
                    :confirmed)
         panel (ui/active-panel db)]
     {:dispatch-n
      (doall
       (->> (list (when confirm? [:review/mark-saving article-id panel])
                  [:action
                   [:review/send-labels
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
