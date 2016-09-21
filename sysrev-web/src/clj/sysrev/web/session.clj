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
    (:sdata (get-session key)))
  (write-session [_ key data]
    (let [key (or key (str (UUID/randomUUID)))
          data (or data {})]
      (let [ss (get-session key)]
        (if (nil? ss)
          (-> (insert-into :session)
              (values [{:skey key
                        :sdata (to-jsonb data)
                        :update_time (sql-now)
                        :user_id (:user-id data)}])
              do-execute)
          (-> (sqlh/update :session)
              (sset {:sdata (to-jsonb data)
                     :update_time (sql-now)
                     :user_id (:user-id data)})
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
