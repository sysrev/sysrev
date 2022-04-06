(ns sysrev.state.user
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.util :refer [in?]]))

(reg-sub ::users #(get-in % [:data :users]))

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

(reg-sub :user/username
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:username %))

(reg-sub :user/permissions
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:permissions %))

(reg-sub :user/dev?
         (fn [[_ user-id]] (subscribe [:user/permissions user-id]))
         #(boolean (in? % "admin")))

(reg-sub :user/api-key
         (fn [[_ user-id]] (subscribe [::user user-id]))
         #(:api-key %))

(reg-sub :user/dev-account-enabled?
         (fn [[_ user-id]] (subscribe [::user user-id]))
         (comp boolean :dev-account-enabled?))
