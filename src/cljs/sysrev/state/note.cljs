(ns sysrev.state.note
  (:require [re-frame.core :refer [subscribe reg-sub reg-sub-raw dispatch
                                   reg-event-db reg-event-fx trim-v]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.action.core :refer [def-action]]))

(reg-sub
 ::note
 (fn [[_ note-name project-id]]
   [(subscribe [:project/notes project-id])])
 (fn [[notes] [_ note-name project-id]] (get notes note-name)))

(reg-sub
 :note/name
 (fn [[_ note-name project-id]]
   [(subscribe [::note note-name project-id])])
 (fn [[note]] (:name note)))

(reg-sub
 :note/description
 (fn [[_ note-name project-id]]
   [(subscribe [::note note-name project-id])])
 (fn [[note]] (:description note)))

(reg-sub
 :article/notes
 (fn [[_ article-id user-id note-name]]
   [(subscribe [:article/raw article-id])])
 (fn [[article] [_ article-id user-id note-name]]
   (cond-> (:notes article)
     user-id (get user-id)
     note-name (get note-name))))

(reg-sub ::ui-notes #(get-in % [:state :review :notes]))

(reg-sub
 :review/ui-notes
 :<- [::ui-notes]
 (fn [ui-notes [_ article-id note-name]]
   (cond-> (get ui-notes article-id)
     note-name (get note-name))))

(reg-sub
 :review/active-note
 (fn [[_ article-id note-name]]
   [(subscribe [:self/user-id])
    (subscribe [:review/ui-notes article-id note-name])
    (subscribe [:article/notes article-id])])
 (fn [[user-id ui-note article-notes] [_ article-id note-name]]
   (let [article-note (get-in article-notes [user-id note-name])]
     (or ui-note article-note))))

(reg-sub
 :review/note-synced?
 (fn [[_ article-id note-name]]
   [(subscribe [:self/user-id])
    (subscribe [:review/ui-notes article-id note-name])
    (subscribe [:article/notes article-id])])
 (fn [[user-id ui-note article-notes] [_ article-id note-name]]
   (let [article-note (get-in article-notes [user-id note-name])]
     (or (nil? ui-note) (= ui-note article-note)))))

(reg-sub
 :review/all-notes-synced?
 (fn [[_ article-id]]
   [(subscribe [:self/user-id])
    (subscribe [:review/ui-notes article-id])
    (subscribe [:article/notes article-id])])
 (fn [[user-id ui-notes article-notes] [_ article-id]]
   (every? #(= (get ui-notes %) (get-in article-notes [user-id %]))
           (keys ui-notes))))

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
 (fn [{:keys [db]} [article-id note-name content]]
   {:dispatch [:action [:article/send-note
                        (active-project-id db)
                        {:article-id article-id
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

(def-action :article/send-note
  :uri (fn [project-id _] "/api/set-article-note")
  :content (fn [project-id {:keys [article-id name content] :as argmap}]
             (merge {:project-id project-id}
                    argmap))
  :process
  (fn [{:keys [db]}
       [project-id {:keys [article-id name content]}]
       result]
    (when-let [user-id (current-user-id db)]
      {:dispatch-n
       (list [:article/set-note-content article-id (keyword name) content]
             [:review/set-note-content article-id (keyword name) nil])})))
