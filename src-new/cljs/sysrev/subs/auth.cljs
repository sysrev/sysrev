(ns sysrev.subs.auth
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub]]
   [sysrev.shared.util :refer [in?]]))

(reg-sub
 ::identity
 (fn [db]
   (get-in db [:state :identity])))

(defn have-identity? [db]
  (contains? (:state db) :identity))
(reg-sub :have-identity? have-identity?)

(defn current-user-id [db]
  (get-in db [:state :identity :user-id]))

(reg-sub :self/user-id current-user-id)

(reg-sub
 :self/logged-in?
 :<- [:self/user-id]
 (fn [user-id] ((comp not nil?) user-id)))

(reg-sub
 ::self-state
 (fn [db] (get-in db [:state :self])))

(reg-sub
 :self/projects
 :<- [::self-state]
 (fn [self] (:projects self)))

(reg-sub
 :self/settings
 :<- [::identity]
 (fn [identity] (:settings identity)))
