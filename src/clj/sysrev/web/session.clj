(ns sysrev.web.session
  (:require
   [ring.middleware.session.store :refer [SessionStore]]
   [sysrev.db.core :as db]
   [sysrev.db.queries :as q]))

(defn get-session [skey]
  (first (q/find :session {:skey skey})))

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
      (let [key (or key (str (random-uuid)))
            data (or data {})
            ss (get-session key)]
        (if (nil? ss)
          (q/create :session {:skey key
                              :sdata (db/to-jsonb data)
                              :update-time (db/sql-now)
                              :user-id (-> data :identity :user-id)})
          (q/modify :session {:skey key}
                    {:sdata (db/to-jsonb data)
                     :update-time (db/sql-now)
                     :user-id (-> data :identity :user-id)}))
        key)))
  (delete-session [_ key]
    (q/delete :session {:skey key})
    nil))

(defn sysrev-session-store []
  (SysrevSessionStore.))
