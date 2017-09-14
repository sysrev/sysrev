(ns sysrev.subs.members
  (:require
   [re-frame.core :as re-frame :refer [subscribe reg-sub reg-sub-raw]]
   [reagent.ratom :refer [reaction]]
   [sysrev.shared.util :refer [in?]]
   [sysrev.subs.project :as project]))

(reg-sub
 ::members
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:members project)))

(reg-sub-raw
 :project/member-user-ids
 (fn [_ [_ project-id include-self-admin?]]
   (reaction
    (let [self-id @(subscribe [:self/user-id])
          members @(subscribe [::members project-id])]
      (->> (keys members)
           (filter
            (fn [user-id]
              (let [admin-user? @(subscribe [:user/admin? user-id])]
                (or (not admin-user?)
                    (and self-id include-self-admin?
                         (= user-id self-id))))))
           (sort <))))))

(reg-sub
 ::member
 (fn [[_ user-id project-id]]
   [(subscribe [::members project-id])
    (subscribe [:self/user-id])])
 (fn [[members self-id] [_ user-id project-id]]
   (get members (or user-id self-id))))

(reg-sub
 :member/permissions
 (fn [[_ user-id project-id]]
   [(subscribe [::member user-id project-id])])
 (fn [[member]]
   (:permissions member)))

(reg-sub
 :member/admin?
 (fn [[_ user-id project-id]]
   [(subscribe [:member/permissions user-id project-id])])
 (fn [[permissions]]
   (in? permissions "admin")))

(reg-sub
 :member/resolver?
 (fn [[_ user-id project-id]]
   [(subscribe [:member/permissions user-id project-id])
    (subscribe [:user/admin? user-id])])
 (fn [[permissions admin-user?]]
   (or admin-user?
       (in? permissions "admin")
       (in? permissions "resolve"))))

(defn have-member-articles? [db user-id project-id]
  (let [project-id (or project-id (project/active-project-id db))
        project (project/get-project-raw db project-id)]
    (contains? (:member-articles project) user-id)))

(reg-sub
 :member/articles
 (fn [[_ user-id project-id]]
   [(subscribe [:project/raw project-id])
    (subscribe [:self/user-id])])
 (fn [[project self-id] [_ user-id _]]
   (let [user-id (or user-id self-id)]
     (get-in project [:member-articles user-id]))))

(reg-sub
 :member/include-count
 (fn [[_ user-id project-id]]
   [(subscribe [::member user-id project-id])])
 (fn [[member]]
   (-> member :articles :includes count)))

(reg-sub
 :member/exclude-count
 (fn [[_ user-id project-id]]
   [(subscribe [::member user-id project-id])])
 (fn [[member]]
   (-> member :articles :excludes count)))

(reg-sub
 :member/article-count
 (fn [[_ user-id project-id]]
   [(subscribe [::member user-id project-id])])
 (fn [[member]]
   (+ (-> member :articles :includes count)
      (-> member :articles :excludes count))))
