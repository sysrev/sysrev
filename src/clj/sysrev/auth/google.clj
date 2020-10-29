(ns sysrev.auth.google
  (:require [sysrev.config :as config])
  (:import [com.google.api.client.http.javanet NetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory]
           #_ [com.google.api.client.auth.oauth2 TokenResponseException]
           [com.google.api.client.googleapis.auth.oauth2
            #_ GoogleTokenResponse #_ GoogleCredential
            GoogleAuthorizationCodeRequestUrl
            GoogleAuthorizationCodeTokenRequest]
           [com.google.api.client.googleapis.auth.oauth2
            #_ GoogleIdToken #_ GoogleIdTokenVerifier #_ GoogleIdToken$Payload
            GoogleIdTokenVerifier$Builder]))

(defonce google-oauth-client-id-server
  "663198182926-l4p6ac774titl403dhr2q3ij00u1qlhl.apps.googleusercontent.com")

(defn google-redirect-url [base-url register?]
  (str base-url (if register?
                  "/api/auth/register/google"
                  "/api/auth/login/google")))

(defn get-google-oauth-url [base-url register?]
  (-> (GoogleAuthorizationCodeRequestUrl.
       google-oauth-client-id-server
       (google-redirect-url base-url register?)
       ["openid" "email" "profile"])
      (.build)))

(defn google-client-secret [] (:google-client-secret config/env))

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

(defn get-google-user-info [base-url auth-code register?]
  (some-> (-> (GoogleAuthorizationCodeTokenRequest.
               (NetHttpTransport.)
               (JacksonFactory.)
               google-oauth-client-id-server
               (google-client-secret)
               auth-code
               (google-redirect-url base-url register?))
              (.execute))
          (.getIdToken)
          (parse-google-id-token)))
