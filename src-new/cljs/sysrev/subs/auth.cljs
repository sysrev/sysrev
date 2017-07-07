(ns sysrev.subs.auth
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub]]
   [sysrev.shared.util :refer [in?]]))

(reg-sub
 ::identity
 (fn [db]
   (get-in db [:state :identity])))

(reg-sub
 :have-identity?
 (fn [db]
   (contains? (:state db) :identity)))

(reg-sub
 :user-id
 :<- [::identity]
 (fn [identity _]
   (:user-id identity)))

(reg-sub
 :logged-in?
 :<- [:user-id]
 (fn [user-id _]
   ((comp not nil?) user-id)))
