(ns sysrev.graphql.token
  (:require [sysrev.db.core :as db]))

(defn getTokenInfo [{:keys [sr-context]} {:keys [token]} _]
  (some->> {:select :user-id
            :from :web-user
            :where [:= :api-token token]}
           (db/execute-one! sr-context)
           :web-user/user-id str (hash-map :userId)))
