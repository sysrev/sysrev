(ns sysrev.subs.users
  (:require
   [re-frame.core :as re-frame :refer [subscribe reg-sub]]
   [sysrev.shared.util :refer [in?]]
   [clojure.string :as str]))

(reg-sub
 ::users
 (fn [db]
   (get-in db [:data :users])))

(reg-sub
 :user/all-user-ids
 (fn []
   [(subscribe [::users])])
 (fn [[users]]
   (keys users)))

(reg-sub
 ::user
 :<- [::users]
 :<- [:user-id]
 (fn [[users self-id] [_ user-id]]
   (let [user-id (or user-id self-id)]
     (when (and users user-id)
       (get users user-id)))))

(reg-sub
 :user/uuid
 (fn [[_ user-id]]
   [(subscribe [::user user-id])])
 (fn [[user]]
   (:user-uuid user)))

(reg-sub
 :user/email
 (fn [[_ user-id]]
   [(subscribe [::user user-id])])
 (fn [[user]]
   (:email user)))

(reg-sub
 :user/display
 (fn [[_ user-id]]
   [(subscribe [:user/email user-id])])
 (fn [[email]]
   (first (str/split email #"@"))))

(reg-sub
 :user/permissions
 (fn [[_ user-id]]
   [(subscribe [::user user-id])])
 (fn [[user]]
   (:permissions user)))

(reg-sub
 :user/admin?
 (fn [[_ user-id]]
   [(subscribe [:user/permissions user-id])])
 (fn [[perms]]
   (boolean (in? perms "admin"))))

(reg-sub
 :user/visible?
 (fn [[_ user-id]]
   [(subscribe [:user/admin? user-id])])
 (fn [[admin?]]
   (not admin?)))

(reg-sub
 :user/project-ids
 (fn [[_ user-id]]
   [(subscribe [::user user-id])])
 (fn [[user]]
   (:projects user)))
