(ns sysrev.subs.notes
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 :project/notes
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ project-id note-name]]
   (cond-> (:notes project)
     note-name (get note-name))))

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
