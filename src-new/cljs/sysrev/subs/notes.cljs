(ns sysrev.subs.notes
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 :project/notes
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project] [_ project-id note-key]]
   (cond-> (:notes project)
     note-key (get note-key))))

(reg-sub
 ::note
 (fn [[_ note-key project-id]]
   [(subscribe [:project/notes project-id])])
 (fn [[notes] [_ note-key project-id]] (get notes note-key)))

(reg-sub
 :note/name
 (fn [[_ note-key project-id]]
   [(subscribe [::note note-key project-id])])
 (fn [[note]] (:name note)))

(reg-sub
 :note/description
 (fn [[_ note-key project-id]]
   [(subscribe [::note note-key project-id])])
 (fn [[note]] (:description note)))

(reg-sub
 :article/notes
 (fn [[_ article-id user-id note-key]]
   [(subscribe [:article/raw article-id])])
 (fn [[article] [_ article-id user-id note-key]]
   (cond-> (:notes article)
     user-id (get user-id)
     note-key (get note-key))))

(reg-sub ::ui-notes #(get-in % [:state :review :notes]))

(reg-sub
 :review/ui-notes
 :<- [::ui-notes]
 (fn [ui-notes [_ article-id note-key]]
   (cond-> (get ui-notes article-id)
     note-key (get note-key))))

(reg-sub
 :review/active-note
 (fn [[_ article-id note-key]]
   [(subscribe [:self/user-id])
    (subscribe [:review/ui-notes article-id note-key])
    (subscribe [:article/notes article-id])])
 (fn [[user-id ui-note article-notes] [_ article-id note-key]]
   (let [article-note (get-in article-notes [user-id note-key])]
     (or ui-note article-note))))

(reg-sub
 :review/note-synced?
 (fn [[_ article-id note-key]]
   [(subscribe [:self/user-id])
    (subscribe [:review/ui-notes article-id note-key])
    (subscribe [:article/notes article-id])])
 (fn [[user-id ui-note article-notes] [_ article-id note-key]]
   (let [article-note (get-in article-notes [user-id note-key])]
     (or (nil? ui-note) (= ui-note article-note)))))
