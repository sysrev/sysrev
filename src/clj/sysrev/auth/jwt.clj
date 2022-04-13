(ns sysrev.auth.jwt
  (:require [buddy.sign.jwt :as jwt]
            [sysrev.db.core :as db]
            [sysrev.util :as util]))

(defn default-jwt []
  (let [now (quot (util/now-ms) 1000)]
    {:exp (+ now 3600)
     :iat now
     :iss "sysrev"}))

(defn sign [sr-context m]
  (jwt/sign
   (merge (default-jwt) m)
   (-> sr-context :config :sysrev-dev-key)))

(defn projects-with-dataset [sr-context dataset-id]
  (->> {:select :project-id
        :from :project-source
        :where [:= :dataset-id dataset-id]}
       (db/execute! sr-context)
       (mapv :project-source/project-id)))

(defn user-project-member? [sr-context user-id project-ids]
  (boolean
   (when (seq project-ids)
     (->> {:select :project-id
           :from :project-member
           :where [:and
                   [:= :user-id user-id]
                   [:in :project-id project-ids]]}
          (db/execute! sr-context)
          seq))))

(defn user-can-access-dataset? [sr-context user-id dataset-id]
  (->> (projects-with-dataset sr-context dataset-id)
       (user-project-member? sr-context user-id)))

(defn dataset-jwt [sr-context user-id dataset-id]
  (when (and user-id dataset-id
             (user-can-access-dataset? sr-context user-id dataset-id))
    (sign sr-context {:dataset-id dataset-id
                      :permissions ["read"]})))
