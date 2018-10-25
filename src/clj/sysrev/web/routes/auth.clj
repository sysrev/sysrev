(ns sysrev.web.routes.auth
  (:require [compojure.core :refer :all]
            [sysrev.api :as api]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.util :refer [should-never-happen-exception]]
            [sysrev.shared.util :refer [in?]]
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
              success (boolean (and valid verified))
              session-identity (select-keys user [:user-id
                                                  :user-uuid
                                                  :email
                                                  :default-project-id])]
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
                 (assoc session :identity session-identity)}))))

  (POST "/api/auth/logout" request
        (let [{{identity :identity :as session} :session} request
              {:keys [settings]} (users/get-user-by-email (:email identity))
              success ((comp not nil?) identity)]
          (with-meta
            {:success success}
            {:session {:settings (select-keys settings [:ui-theme])}})))
  
  (POST "/api/auth/register" request
        (let [{{:keys [email password project-id]
                :or {project-id nil}
                :as body} :body} request]
          (api/register-user! email password project-id)))
  
  (GET "/api/auth/identity" request
       (let [{{{:keys [user-id] :as identity} :identity
               :as session} :session} request]
         (if user-id
           (merge
            {:identity (users/user-identity-info user-id true)}
            (users/user-self-info user-id))
           {:identity {:settings (:settings session)}})))

  (POST "/api/auth/change-session-settings" request
        (let [{:keys [body session]} request
              {:keys [settings]} body]
          (assert (or (nil? settings) (map? settings)))
          (with-meta
            {:success true
             :settings settings}
            {:session (merge session {:settings settings})})))

  (GET "/api/auth/lookup-reset-code" request
       (let [{{:keys [reset-code] :as params}
              :params} request
             {:keys [email]} (users/get-user-by-reset-code reset-code)]
         {:email email}))

  (POST "/api/auth/request-password-reset" request
        (let [{{:keys [email url-base] :as body}
               :body} request
              {:keys [user-id]
               :as user} (users/get-user-by-email email)]
          (if (nil? user)
            {:result
             {:success false
              :exists false}}
            (do
              (send-password-reset-email user-id :url-base url-base)
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
          {:success true})))

(defn send-password-reset-email
  [user-id & {:keys [url-base]
              :or {url-base "https://sysrev.com"}}]
  (let [{:keys [email] :as user}
        (users/get-user-by-id user-id)]
    (users/create-password-reset-code user-id)
    (send-email
     email "Sysrev Password Reset Requested"
     (with-out-str
       (printf "A password reset has been requested for email address %s on %s\n\n"
               email url-base)
       (printf "If you made this request, follow this link to reset your password: %s\n\n"
               (users/user-password-reset-url user-id :url-base url-base))))))
