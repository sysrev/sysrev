(ns sysrev.events.auth
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe trim-v]]
   [sysrev.routes :refer [nav nav-scroll-top force-dispatch]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.shared.util :refer [to-uuid]]))

(reg-event-db
 :set-identity
 [trim-v]
 (fn [db [imap]]
   (let [imap (some-> imap (update :user-uuid to-uuid))]
     (assoc-in db [:state :identity] imap))))

(reg-event-db
 :unset-identity
 (fn [db]
   (dissoc-in db [:state :identity])))

(reg-event-db
 :set-active-project
 [trim-v]
 (fn [db [project-id]]
   (assoc-in db [:state :active-project-id] project-id)))

(reg-event-db
 :set-login-redirect-url
 [trim-v]
 (fn [db [url]]
   (assoc db :login-redirect url)))

(reg-event-db
 :do-login-redirect
 (fn [db]
   (let [url @(subscribe [:login-redirect-url])]
     (nav-scroll-top url)
     (force-dispatch url)
     (dissoc db :login-redirect))))
