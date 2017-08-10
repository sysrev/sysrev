(ns sysrev.events.notes
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-event-db reg-event-fx trim-v]]))

(reg-event-db
 :review/set-note-content
 [trim-v]
 (fn [db [article-id note-key content]]
   (assoc-in db [:state :review :notes article-id note-key] content)))

(reg-event-db
 :article/set-note-content
 [trim-v]
 (fn [db [article-id note-key content]]
   (assoc-in db [:data :articles article-id :notes note-key] content)))

(reg-event-fx
 :review/send-article-note
 [trim-v]
 (fn [_ [article-id note-key content]]
   (let [note-name (name note-key)]
     {:dispatch [:action [:article/send-note {:article-id article-id
                                              :name (name note-key)
                                              :content content}]]})))

(defn sync-article-notes [article-id]
  (let [user-id @(subscribe [:self/user-id])
        ui-notes @(subscribe [:review/ui-notes article-id])
        article-notes @(subscribe [:article/notes article-id user-id])
        changed-keys (->> (keys ui-notes)
                          (filterv #(not= (get ui-notes %)
                                          (get article-notes %))))]
    (doseq [note-key changed-keys]
      (dispatch [:review/send-article-note
                 article-id note-key (get ui-notes note-key)]))
    changed-keys))
