(ns sysrev.web.session
  (:require [ring.middleware.session.store :refer [SessionStore]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :as db :refer [do-query do-execute]]
            [sysrev.db.queries :as q])
  (:import [java.util UUID]))

(defn get-session [skey]
  (-> (select :*)
      (from :session)
      (where [:= :skey skey])
      do-query
      first))

(deftype SysrevSessionStore []
  SessionStore
  (read-session [_ key]
    (let [data (:sdata (get-session key))]
      (-> data
          (assoc (keyword "ring.middleware.anti-forgery"
                          "anti-forgery-token")
                 (:anti-forgery-token data))
          (dissoc :anti-forgery-token))))
  (write-session [_ key data]
    (db/with-transaction
      (let [key (or key (str (UUID/randomUUID)))
            data (or data {})]
        (let [ss (get-session key)]
          (if (nil? ss)
            (-> (insert-into :session)
                (values [{:skey key
                          :sdata (db/to-jsonb data)
                          :update-time (db/sql-now)
                          :user-id (-> data :identity :user-id)}])
                do-execute)
            (-> (sqlh/update :session)
                (sset {:sdata (db/to-jsonb data)
                       :update-time (db/sql-now)
                       :user-id (-> data :identity :user-id)})
                (where [:= :skey key])
                do-execute)))
        key)))
  (delete-session [_ key]
    (q/delete-by-id :session :skey key)
    nil))

(defn sysrev-session-store []
  (SysrevSessionStore.))
