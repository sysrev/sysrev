(ns sysrev.state.identity
  (:require
   [re-frame.core :refer [subscribe reg-sub reg-event-db reg-event-fx
                          dispatch trim-v reg-fx]]
   [sysrev.nav :refer [nav nav-scroll-top force-dispatch]]
   [sysrev.state.nav :refer [get-login-redirect-url]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.shared.util :refer [in? to-uuid]]))

(reg-event-db
 :self/set-identity
 [trim-v]
 (fn [db [imap]]
   (let [imap (some-> imap (update :user-uuid to-uuid))]
     (assoc-in db [:state :identity] imap))))

(reg-event-db
 :self/unset-identity
 (fn [db]
   (dissoc-in db [:state :identity])))

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

(reg-event-db
 :self/set-projects
 [trim-v]
 (fn [db [projects]]
   (assoc-in db [:state :self :projects] projects)))

(reg-sub
 :self/projects
 :<- [::self-state]
 (fn [{:keys [projects]} [_ include-available?]]
   (if include-available?
     projects
     (->> projects (filterv :member?)))))

(reg-sub
 :self/default-project-id
 :<- [::identity]
 (fn [{:keys [default-project-id]}]
   default-project-id))

(reg-sub
 :self/member?
 :<- [:self/projects false]
 :<- [:active-project-id]
 (fn [[projects active-id] [_ project-id]]
   (let [project-id (or project-id active-id)]
     (in? (map :project-id projects) project-id))))

(reg-event-fx
 :self/load-settings
 [trim-v]
 (fn [{:keys [db]} [settings]]
   (let [old-settings (get-in db [:state :identity :settings])]
     (cond->
         {:db (assoc-in db [:state :identity :settings] settings)}
       (not= (:ui-theme settings) (:ui-theme old-settings))
       (merge {:reload-page [true]})))))

(reg-sub
 :self/settings
 :<- [::identity]
 (fn [identity] (:settings identity)))

(reg-event-db
 :load-project-url-ids
 [trim-v]
 (fn [db [url-ids-map]]
   (update-in db [:data :project-url-ids]
              #(merge % url-ids-map))))
