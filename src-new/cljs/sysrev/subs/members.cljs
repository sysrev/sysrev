(ns sysrev.subs.members
  (:require
   [re-frame.core :as re-frame :refer [subscribe reg-sub]]
   [sysrev.shared.util :refer [in?]]))

(reg-sub
 ::members
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:members project)))

(reg-sub
 :project/member-user-ids
 (fn [[_ project-id]]
   [(subscribe [::members project-id])])
 (fn [[members]]
   (keys members)))

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
