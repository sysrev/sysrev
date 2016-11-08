(ns sysrev.web.auth
  (:require [clojure.data.json :as json]
            [ring.util.response :as r]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.util :refer [should-never-happen-exception]]))

(defn web-login-handler [request]
  (let [{session :session
         {:keys [email password] :as body} :body} request
        valid (users/valid-password? email password)
        user (when valid (users/get-user-by-email email))
        {verified :verified :or {verified false}} user
        success (boolean (and valid verified))]
    (cond->
        {:success success
         :valid valid
         :verified verified}
      (not valid)
      (assoc :message "Invalid username or password")
      (and valid (not verified))
      (assoc :message "Your account's email has not been verified yet")
      success
      (with-meta
        {:session
         (assoc session :identity
                (select-keys
                 user [:user-id :user-uuid :email]))}))))

(defn web-logout-handler [request]
  (let [{{identity :identity :as session} :session} request
        success ((comp not nil?) identity)]
    (with-meta
      {:success success}
      {:session (assoc session :identity nil)})))

(defn web-create-account-handler [request]
  (let [{{:keys [email password project-id]
          :or {project-id (-> (project/get-default-project) :project-id)}
          :as body} :body} request
        _ (assert (string? email))
        user (users/get-user-by-email email)
        db-result
        (when-not user
          (try
            (users/create-user email password :project-id project-id)
            true
            (catch Throwable e
              e)))]
    (cond
      user
      {:result
       {:success false
        :message "Account already exists for this email address"}}
      (isa? (type db-result) Throwable)
      {:error
       {:status 500
        :message "An error occurred while creating account"
        :exception db-result}}
      (true? db-result)
      {:result
       {:success true}}
      :else (throw (should-never-happen-exception)))))

(defn web-get-identity [request]
  (let [{{{:keys [user-id user-uuid email] :as identity} :identity
          :as session} :session} request]
    {:identity (if (and user-id user-uuid email)
                 {:id user-id :user-uuid user-uuid :email email}
                 nil)}))
