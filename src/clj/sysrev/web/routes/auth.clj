(ns sysrev.web.routes.auth
  (:require [compojure.core :refer :all]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.util :refer [should-never-happen-exception]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.web.routes.site :refer
             [user-identity-info user-self-info]]
            [sysrev.mail.core :refer [send-email]]
            [sysrev.db.core :as db]
            [sysrev.config.core :refer [env]]))

(declare send-password-reset-email)

(defroutes auth-routes
  (POST "/api/auth/login" request
        (let [{session :session
               {:keys [email password] :as body} :body} request
              valid (or (and (= :dev (:profile env))
                             (= password "override"))
                        (users/valid-password? email password))
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
               (assoc session
                      :identity (select-keys
                                 user [:user-id :user-uuid :email])
                      :active-project (:default-project-id user))}))))

  (POST "/api/auth/logout" request
        (let [{{identity :identity :as session} :session} request
              success ((comp not nil?) identity)]
          (with-meta
            {:success success}
            {:session {}})))
  
  (POST "/api/auth/register" request
        (let [{{:keys [email password project-id]
                :or {project-id nil}
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
  
  (GET "/api/auth/identity" request
       (let [{{{:keys [user-id] :as identity} :identity
               active-project :active-project
               :as session} :session} request]
         (if user-id
           (merge
            {:identity (user-identity-info user-id true)
             :active-project active-project}
            (user-self-info user-id))
           {:identity nil
            :active-project nil})))

  (GET "/api/auth/lookup-reset-code" request
       (let [{{:keys [reset-code] :as params}
              :params} request
             {:keys [email]} (users/get-user-by-reset-code reset-code)]
         {:email email}))

  (POST "/api/auth/request-password-reset" request
        (let [{{:keys [email] :as body}
               :body} request
              {:keys [user-id]
               :as user} (users/get-user-by-email email)]
          (if (nil? user)
            {:result
             {:success false
              :exists false}}
            (do
              (send-password-reset-email user-id)
              {:success true
               :exists true}))))

  (POST "/api/auth/reset-password" request
        (let [{{:keys [reset-code password] :as body}
               :body} request
              {:keys [email user-id]
               :as user} (users/get-user-by-reset-code reset-code)]
          (assert user-id "No user account found for reset code")
          (users/set-user-password email password)
          (users/clear-password-reset-code user-id)
          (db/clear-user-cache user-id)
          {:success true})))

(defn send-password-reset-email [user-id]
  (let [{:keys [email] :as user}
        (users/get-user-by-id user-id)]
    (users/create-password-reset-code user-id)
    (db/clear-user-cache user-id)
    (send-email
     email "SysRev.us Password Reset Requested"
     (with-out-str
       (printf "A password reset has been requested for email address %s on https://sysrev.us\n\n" email)
       (printf "If you made this request, follow this link to reset your password: %s\n\n"
               (users/user-password-reset-url user-id))))))
