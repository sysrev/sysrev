(ns sysrev.events.notes
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-event-db reg-event-fx trim-v]]))

(reg-event-db
 :review/reset-ui-notes
 [trim-v]
 (fn [db []]
   (assoc-in db [:state :review :notes] {})))

(reg-event-db
 :review/set-note-content
 [trim-v]
 (fn [db [article-id note-name content]]
   (assoc-in db [:state :review :notes article-id note-name] content)))

(reg-event-db
 :article/set-note-content
 [trim-v]
 (fn [db [article-id note-name content]]
   (assoc-in db [:data :articles article-id :notes note-name] content)))

(reg-event-fx
 :review/send-article-note
 [trim-v]
 (fn [_ [article-id note-name content]]
   {:dispatch [:action [:article/send-note {:article-id article-id
                                            :name note-name
                                            :content content}]]}))

(reg-event-fx
 :review/sync-article-notes
 [trim-v]
 (fn [_ [article-id ui-notes article-notes]]
   (let [changed-keys (->> (keys ui-notes)
                           (filterv #(not= (get ui-notes %)
                                           (get article-notes %))))]
     {:dispatch-n
      (doall
       (map (fn [note-name]
              [:review/send-article-note
               article-id note-name (get ui-notes note-name)])
            changed-keys))})))

(defn sync-article-notes [article-id]
  (let [user-id @(subscribe [:self/user-id])
        ui-notes @(subscribe [:review/ui-notes article-id])
        article-notes @(subscribe [:article/notes article-id user-id])]
    (dispatch [:review/sync-article-notes
               article-id ui-notes article-notes])))
