(ns sysrev.events.auth
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx dispatch trim-v reg-fx]]
   [sysrev.routes :refer [nav nav-scroll-top force-dispatch]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.shared.util :refer [to-uuid]]
   [sysrev.subs.ui :refer [get-login-redirect-url]]))

(reg-event-fx
 :reset-data
 (fn [{:keys [db]}]
   {:db (-> db
            (assoc :data {}
                   :needed [])
            (dissoc-in [:state :review])
            (dissoc-in [:state :panels]))
    :dispatch [:fetch [:identity]]
    :fetch-missing true}))

(reg-fx
 :reset-data
 (fn [reset?] (when reset? (dispatch [:reset-data]))))

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

(reg-event-fx
 :self/set-active-project
 [trim-v]
 (fn [{:keys [db]} [project-id]]
   (let [old-id (get-in db [:state :active-project-id])
         changed? (and old-id (not= old-id project-id))]
     (cond-> {:db (assoc-in db [:state :active-project-id] project-id)}
       changed?
       (merge {:reset-data true})))))

(reg-event-db
 :self/set-projects
 [trim-v]
 (fn [db [projects]]
   (assoc-in db [:state :self :projects] projects)))

(reg-event-fx
 :self/load-settings
 [trim-v]
 (fn [{:keys [db]} [settings]]
   (let [old-settings (get-in db [:state :identity :settings])]
     (cond->
         {:db (assoc-in db [:state :identity :settings] settings)}
       (not= (:ui-theme settings) (:ui-theme old-settings))
       (merge {:reload-page [true]})))))

(reg-event-db
 :set-login-redirect-url
 [trim-v]
 (fn [db [url]]
   (assoc db :login-redirect url)))

(reg-event-db
 :do-login-redirect
 (fn [db]
   (let [url (get-login-redirect-url db)]
     (nav-scroll-top url)
     (force-dispatch url)
     (dissoc db :login-redirect))))
