(ns sysrev-web.login
  (:require [sysrev-web.base :refer [state server-data]]))

(defn current-user-data []
  (let [user-id (:id (:user @state))
        user-pred (fn [u] (= user-id (-> u :user :id)))
        all-users (:users @server-data)]
    (first (filter user-pred all-users))))
