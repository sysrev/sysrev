(ns sysrev.web.routes.auth
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST]]
            [ring.util.response :as response]
            [sysrev.api :as api]
            [sysrev.user.core :as user :refer [user-by-email]]
            [sysrev.mail.core :refer [send-email]]
            [sysrev.web.routes.core :refer [setup-local-routes]]
            [sysrev.web.app :as web :refer [with-authorize]]
            [sysrev.config :refer [env]])
  (:import com.google.api.client.http.javanet.NetHttpTransport
           com.google.api.client.json.jackson2.JacksonFactory
           #_ com.google.api.client.auth.oauth2.TokenResponseException
           (com.google.api.client.googleapis.auth.oauth2
            #_ GoogleTokenResponse #_ GoogleCredential
            GoogleAuthorizationCodeRequestUrl
            GoogleAuthorizationCodeTokenRequest)
           (com.google.api.client.googleapis.auth.oauth2
            #_ GoogleIdToken #_ GoogleIdTokenVerifier
            GoogleIdTokenVerifier$Builder #_ GoogleIdToken$Payload)))

;; for clj-kondo
(declare auth-routes dr finalize-routes)

(declare send-password-reset-email)

(defonce google-oauth-client-id-server
  "663198182926-l4p6ac774titl403dhr2q3ij00u1qlhl.apps.googleusercontent.com")

(defn google-redirect-url [base-url]
  (str base-url "/api/auth/login/google"))

(defn get-google-oauth-url [base-url]
  (-> (GoogleAuthorizationCodeRequestUrl.
       google-oauth-client-id-server
       (google-redirect-url base-url)
       ["openid" "email" "profile"])
      (.build)))

(defn google-client-secret [] (:google-client-secret env))

(defn parse-google-id-token [id-token-str]
  (let [verifier (-> (GoogleIdTokenVerifier$Builder.
                      (NetHttpTransport.)
                      (JacksonFactory.))
                     (.setAudience [google-oauth-client-id-server])
                     (.build))
        id-token (-> verifier (.verify id-token-str))]
    (when id-token
      (let [payload (.getPayload id-token)]
        {:google-user-id (.getSubject payload)
         :email (.getEmail payload)
         :name (.get payload "name")}))))

(defn get-google-user-info [base-url auth-code]
  (let [response (-> (GoogleAuthorizationCodeTokenRequest.
                      (NetHttpTransport.)
                      (JacksonFactory.)
                      google-oauth-client-id-server
                      (google-client-secret)
                      auth-code
                      (google-redirect-url base-url))
                     (.execute))
        id-token-str (.getIdToken response)]
    (parse-google-id-token id-token-str)))

(setup-local-routes {:routes auth-routes
                     :define dr
                     :finalize finalize-routes})

(dr (GET "/api/auth/google-oauth-url" request
         (let [{:keys [base-url]} (:params request)]
           {:result (get-google-oauth-url base-url)})))

(dr (GET "/api/auth/login/google" request
         (let [{:keys [params scheme server-name session]} request
               {:keys [code]} params
               base-url (str (name scheme) "://" server-name)
               {:keys [email #_ google-user-id] :as user-info}
               (try (get-google-user-info base-url code)
                    (catch Throwable e
                      (log/warn "get-google-user-info login failure")
                      (log/warn (.getMessage e))
                      nil))
               user (when user-info (user-by-email email))
               {_verified :verified :or {_verified false}} user
               _success (not-empty user)
               session-identity (select-keys user [:user-id :user-uuid :email :default-project-id])]
           (with-meta (response/redirect base-url)
             {:session (assoc session :identity session-identity)}))))

(dr (POST "/api/auth/login" request
          (let [{:keys [session body]} request
                {:keys [email password]} body
                valid (or (and (= :dev (:profile env))
                               (= password "override"))
                          (user/valid-password? email password))
                user (when valid (user-by-email email))
                success (boolean valid)
                session-identity (select-keys user [:user-id :user-uuid :email :default-project-id])
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
          (let [{:keys [email password project-id]} (:body request)]
            (api/register-user! email password project-id))))

(dr (GET "/api/auth/identity" request
         (let [{:keys [session]} request
               {:keys [user-id]} (:identity session)
               verified (user/primary-email-verified? user-id)]
           (if user-id
             (-> (merge {:identity (user/user-identity-info user-id true)}
                        (user/user-self-info user-id)
                        {:orgs (:orgs (api/read-orgs user-id))})
                 (assoc-in [:identity :verified] verified))
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
              (do (send-password-reset-email user-id :url-base url-base)
                  {:success true, :exists true})
              {:success false, :exists false}))))

(dr (POST "/api/auth/reset-password" request
          (let [{:keys [reset-code password]} (:body request)
                {:keys [email user-id]} (user/user-by-reset-code reset-code)]
            (assert user-id "No user account found for reset code")
            (user/set-user-password email password)
            (user/clear-password-reset-code user-id)
            {:success true})))

(dr (GET "/api/stripe/connected/:user-id" request
         (with-authorize request {:logged-in true}
           (let [user-id (-> request :params :user-id Integer/parseInt)]
             (api/user-has-stripe-account? user-id)))))
;; unused
(dr (POST "/api/stripe/finalize-user" request
          (with-authorize request {:logged-in true}
            (let [{:keys [user-id stripe-code]} (-> request :body)]
              (api/finalize-stripe-user! user-id stripe-code)))))

(finalize-routes)

(defn send-password-reset-email [user-id & {:keys [url-base]
                                            :or {url-base "https://sysrev.com"}}]
  (let [email (user/get-user user-id :email)]
    (user/create-password-reset-code user-id)
    (send-email
     email "Sysrev Password Reset Requested"
     (with-out-str
       (printf "A password reset has been requested for email address %s on %s\n\n"
               email url-base)
       (printf "If you made this request, follow this link to reset your password: %s\n\n"
               (user/user-password-reset-url user-id :url-base url-base))))))
