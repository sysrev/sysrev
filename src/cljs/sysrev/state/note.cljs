(ns sysrev.state.note
  (:require [re-frame.core :refer [subscribe reg-sub dispatch reg-event-db reg-event-fx trim-v]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.action.core :refer [def-action]]))

(reg-sub :article/notes
         (fn [[_ article-id _]] (subscribe [:article/raw article-id]))
         (fn [article [_ _ user-id]]
           (cond-> (:notes article)
             user-id (get user-id))))

(reg-sub ::ui-notes #(get-in % [:state :review :notes]))

(reg-sub :review/ui-notes
         (fn [db [_ article-id]]
           (get-in db [:state :review :notes article-id])))

(reg-sub :review/active-note
         (fn [[_ article-id]]
           [(subscribe [:self/user-id])
            (subscribe [:review/ui-notes article-id])
            (subscribe [:article/notes article-id])])
         (fn [[user-id ui-note article-notes] _]
           (let [article-note (get article-notes user-id)]
             (or ui-note article-note))))

(reg-sub :review/all-notes-synced?
         (fn [[_ article-id]]
           [(subscribe [:self/user-id])
            (subscribe [:review/ui-notes article-id])
            (subscribe [:article/notes article-id])])
         (fn [[user-id ui-notes article-notes]]
           (every? #(= (get ui-notes %) (get-in article-notes [user-id %]))
                   (keys ui-notes))))

(reg-event-db :review/reset-ui-notes [trim-v]
              (fn [db _]
                (assoc-in db [:state :review :notes] {})))

(reg-event-db :review/set-note-content [trim-v]
              (fn [db [article-id content]]
                (assoc-in db [:state :review :notes article-id] content)))

(reg-event-fx :review/send-article-note [trim-v]
              (fn [{:keys [db]} [article-id content]]
                {:dispatch [:action [:article/send-note (active-project-id db)
                                     {:article-id article-id
                                      :content content}]]}))

(reg-event-fx :review/sync-article-notes [trim-v]
              (fn [_ [article-id ui-notes article-notes]]
                (when (not= ui-notes article-notes)
                  {:dispatch [:review/send-article-note article-id ui-notes]})))

(defn sync-article-notes [article-id]
  (let [user-id @(subscribe [:self/user-id])
        ui-notes @(subscribe [:review/ui-notes article-id])
        article-notes @(subscribe [:article/notes article-id user-id])]
    (dispatch [:review/sync-article-notes article-id ui-notes article-notes])))

(reg-event-db ::load-article-note [trim-v]
              (fn [db [article-id content]]
                (let [self-id (current-user-id db)]
                  (assoc-in db [:data :articles article-id :notes self-id] content))))

(def-action :article/send-note
  :uri (constantly "/api/set-article-note")
  :content (fn [project-id {:keys [article-id content] :as note}]
             (merge note {:project-id project-id}))
  :process (fn [{:keys [db]} [_ {:keys [article-id content]}] _]
             (when (current-user-id db)
               {:dispatch-n (list [::load-article-note article-id content]
                                  [:review/set-note-content article-id nil])})))
