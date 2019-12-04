(ns sysrev.state.user
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.shared.util :refer [in?]]))

(reg-sub ::users #(get-in % [:data :users]))

(reg-sub :user/all-user-ids
         :<- [::users]
         #(keys %))

(reg-sub ::user
         :<- [::users]
         :<- [:self/user-id]
         (fn [[users self-id] [_ user-id]]
           (let [user-id (or user-id self-id)]
             (when (and users user-id)
               (get users user-id)))))

(reg-sub :user/uuid
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:user-uuid %))

(reg-sub :user/email
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:email %))

(reg-sub :user/display
         (fn [[_ user-id]] (subscribe [:user/email user-id]))
         #(first (str/split % #"@")))

(reg-sub :user/permissions
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:permissions %))

(reg-sub :user/admin?
         (fn [[_ user-id]] (subscribe [:user/permissions user-id]))
         #(boolean (in? % "admin")))

(reg-sub :user/visible?
         (fn [[_ user-id _include-self-admin?]]
           [(subscribe [:self/user-id])
            (subscribe [:user/admin? user-id])])
         (fn [[self-id admin?] [_ user-id include-self-admin?]]
           (or (not admin?)
               (and include-self-admin? (= user-id self-id)))))

(reg-sub :user/project-ids
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:projects %))

;; Hacky detection of real (not browser test) admin users based on email
(reg-sub :user/actual-admin?
         (fn [[_ user-id]]
           [(subscribe [:user/admin? user-id])
            (subscribe [:user/email user-id])])
         (fn [[admin? email]]
           (and admin? email
                (not (or (str/includes? email "browser")
                         (str/includes? email "test")))
                (or (str/includes? email "insilica.co")
                    (str/includes? email "tomluec")))))
