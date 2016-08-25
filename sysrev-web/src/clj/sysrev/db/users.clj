(ns sysrev.db.users
  (:require [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn all-user-emails []
  (->>
   (-> (select :email)
       (from :web_user)
       do-query)
   (mapv :email)))

(defn get-user-by-email [email]
  (-> (select :*)
      (from :web_user)
      (where [:= :email email])
      do-query
      first))

(defn create-user [email password]
  nil)
