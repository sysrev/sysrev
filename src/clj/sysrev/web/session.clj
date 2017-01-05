(ns sysrev.web.session
  (:require
   [ring.middleware.session.store :refer [SessionStore]]
   [sysrev.db.core :refer [to-jsonb sql-now do-query do-execute]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]])
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
    (let [key (or key (str (UUID/randomUUID)))
          data (or data {})]
      (let [ss (get-session key)]
        (if (nil? ss)
          (-> (insert-into :session)
              (values [{:skey key
                        :sdata (to-jsonb data)
                        :update-time (sql-now)
                        :user-id (-> data :identity :user-id)}])
              do-execute)
          (-> (sqlh/update :session)
              (sset {:sdata (to-jsonb data)
                     :update-time (sql-now)
                     :user-id (-> data :identity :user-id)})
              (where [:= :skey key])
              do-execute)))
      key))
  (delete-session [_ key]
    (-> (delete-from :session)
        (where [:= :skey key])
        do-execute)
    nil))

(defn sysrev-session-store []
  (SysrevSessionStore.))
