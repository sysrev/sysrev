(ns sysrev.web.routes.auth
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST]]
            [ring.util.response :as response]
            [sysrev.api :as api]
            [sysrev.auth.google :as google]
            [sysrev.auth.jwt :as jwt]
            [sysrev.config :refer [env]]
            [sysrev.db.queries :as q]
            [sysrev.mail.core :as mail]
            [sysrev.user.interface :as user :refer [user-by-email]]
            [sysrev.web.app :as app]
            [sysrev.web.routes.core :refer [setup-local-routes]]))

;; for clj-kondo
(declare auth-routes dr finalize-routes)

(setup-local-routes {:routes auth-routes
                     :define dr
                     :finalize finalize-routes})

(dr (GET "/api/auth/google-oauth-url" request
      (let [{:keys [params]} request
            {:keys [base-url register]} params
            url (google/get-google-oauth-url base-url (= register "true"))]
        (-> {:body {:result url}}
            (response/set-cookie "_baseurl" base-url
                                 {:same-site :lax, :domain ""})))))

(dr (GET "/api/auth/login/google" request
      (let [{:keys [params session cookies]} request
            {:keys [code]} params
            base-url (get-in cookies ["_baseurl" :value])
            {:keys [email google-user-id] :as user-info}
            (try (google/get-google-user-info base-url code false)
                 (catch Throwable e
                   (log/warn "get-google-user-info login failure")
                   (log/warn (.getMessage e))
                   nil))
            {:keys [user-id] :as user} (when user-info (user-by-email email))
               ;; {verified :verified :or {verified false}} user
               ;; _success (not-empty user)
            session-identity (select-keys user [:user-id :user-uuid :email])
            get-redirect (fn [& [rpath rparams]]
                           (str base-url rpath
                                (some->> rparams http/generate-query-string (str "?"))))
            url (cond user             (get-redirect)
                      (nil? user-info) (get-redirect "/login" {:auth-error "google-login"})
                      :else            (get-redirect "/login" {:auth-error "sysrev-login"
                                                               :auth-email email}))]
        (when (and user (some-> (not-empty google-user-id)
                                (not= (:google-user-id user))))
          (q/modify :web-user {:user-id user-id} {:google-user-id google-user-id}))
        (when (and user (nil? (:date-google-login user)))
          (q/modify :web-user {:user-id user-id} {:date-google-login :%now}))
        (with-meta (response/redirect url)
          {:session (assoc session :identity session-identity)}))))

(dr (POST "/api/auth/login" request
      (let [{:keys [session body]} request
            {:keys [email password]} body
            valid (or (and (= :dev (:profile env))
                           (= password "override"))
                      (user/valid-password? email password))
            user (when valid (user-by-email email))
            success (boolean valid)
            session-identity (select-keys user [:user-id :user-uuid :email])
            verified (user/primary-email-verified? (:user-id session-identity))]
        (cond-> {:success success, :valid valid, :verified verified}
          (not valid) (assoc :message "Invalid username or password")
          success     (with-meta {:session (assoc session :identity session-identity)})))))

(dr (POST "/api/auth/logout" request
      (let [{:keys [session]} request
            {:keys [identity]} session
            settings (user-by-email (:email identity) :settings)
            success ((comp not nil?) identity)]
        (with-meta {:success success}
          {:session {:settings (select-keys settings [:ui-theme])}}))))

(dr (POST "/api/auth/register" request
      (let [{:keys [email password project-id org-id]} (:body request)]
        (api/register-user! :email email :password password
                            :project-id project-id :org-id org-id))))

(dr (GET "/api/auth/register/google" request
      (let [{:keys [params session cookies]} request
            {:keys [code]} params
            base-url (get-in cookies ["_baseurl" :value])
            {:keys [email google-user-id] :as user-info}
            (try (google/get-google-user-info base-url code true)
                 (catch Throwable e
                   (log/warn "get-google-user-info register failure")
                   (log/warn (.getMessage e))
                   nil))
            {:keys [success] :as result} (when email
                                           (api/register-user!
                                            :email email
                                            :google-user-id google-user-id))
            user (when success (some-> email user-by-email))
            get-redirect (fn [& [rpath rparams]]
                           (str base-url rpath
                                (some->> rparams http/generate-query-string (str "?"))))
            url (cond user             (get-redirect)
                      (nil? user-info) (get-redirect "/register"
                                                     {:auth-error "google-signup"})
                      (not success)    (get-redirect "/register"
                                                     {:auth-error (:message result)})
                      :else            (get-redirect "/register"
                                                     {:auth-error "sysrev-signup"}))]
        (if user
          (with-meta (response/redirect url)
            {:session (assoc session :identity
                             (select-keys user [:user-id :user-uuid :email]))})
          (response/redirect url)))))

(dr (GET "/api/auth/identity" request
      (let [{:keys [session]} request
            {:keys [user-id]} (:identity session)
            verified (user/primary-email-verified? user-id)]
        (if user-id
          (-> (merge {:identity (user/user-identity-info user-id)}
                     (user/user-self-info user-id)
                     {:orgs (:orgs (api/read-orgs user-id))})
              (assoc-in [:identity :verified] verified)
              (assoc-in [:identity :dev-account-enabled?] (api/datasource-account-enabled? user-id)))
          {:identity {:settings (:settings session)}}))))

(dr (POST "/api/auth/change-session-settings" request
      (let [{:keys [body session]} request
            {:keys [settings]} body]
        (assert (or (nil? settings) (map? settings)))
        (with-meta {:success true, :settings settings}
          {:session (merge session {:settings settings})}))))

(dr (GET "/api/auth/lookup-reset-code" request
      (let [{:keys [reset-code]} (:params request)
            {:keys [email]} (user/user-by-reset-code reset-code)]
        {:email email})))

(dr (POST "/api/auth/request-password-reset" request
      (let [{:keys [email url-base]} (:body request)]
        (if-let [user-id (user-by-email email :user-id)]
          (do (mail/send-password-reset-email user-id :url-base url-base)
              {:success true, :exists true})
          {:success false, :exists false}))))

(dr (POST "/api/auth/reset-password" request
      (let [{:keys [reset-code password]} (:body request)
            {:keys [email user-id]} (user/user-by-reset-code reset-code)]
        (when-not user-id
          (throw (RuntimeException. "No user account found for reset code")))
        (user/set-user-password email password)
        (api/change-datasource-password! user-id)
        (user/clear-password-reset-code user-id)
        {:success true})))

(dr (POST "/api/auth/jwt/dataset" {:keys [body sr-context] :as request}
      (let [dataset-id (:dataset-id body)
            user-id (app/current-user-id request)]
        (if-let [jwt (jwt/dataset-jwt sr-context user-id dataset-id)]
          {:success true :jwt jwt}
          (throw (ex-info "Unauthorized" {:dataset-id dataset-id :user-id user-id}))))))

(finalize-routes)
