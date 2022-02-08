(ns sysrev.sysrev-api.user
  (:require
   [sysrev.sysrev-api.core :as core
    :refer [execute-one! with-tx-context]]))

(defn bearer-token [context]
  (some-> context :request :headers (get "authorization")
          (->> (re-matches #"(?i)Bearer (.*)"))
          second))

(defn current-user-id [context]
  (when-let [token (bearer-token context)]
    (with-tx-context [context context]
      (-> context
          (execute-one! {:select :user-id
                         :from :web-user
                         :where [:= :api-token token]})
          :web-user/user-id))))
