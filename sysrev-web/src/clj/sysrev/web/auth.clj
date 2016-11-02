(ns sysrev.web.auth
  (:require [clojure.data.json :as json]
            [ring.util.response :as r]
            [sysrev.db.users :as users]
            [sysrev.web.ajax :refer [wrap-json get-user-id]]
            [sysrev.db.project :as project]))

(defn web-login-handler [request]
  (let [session (:session request)
        fields (-> request :body slurp (json/read-str :key-fn keyword))
        user (users/get-user-by-email (:email fields))
        auth-session (assoc session :identity
                            (select-keys user [:user-id :user-uuid :email]))
        valid-pw (users/valid-password? (:email fields) (:password fields))
        verified (and valid-pw (true? (:verified user)))
        success? (and valid-pw verified)
        result {:valid valid-pw :verified verified}
        response
        (if success?
          (-> result wrap-json (assoc :session auth-session))
          (-> result (assoc :err "Invalid username or password") wrap-json))]
    response))

(defn web-logout-handler [request]
  (let [session (:session request)
        success? (not (nil? (:identity session)))
        result {:success (if success? true false)}
        response
        (-> result
            wrap-json
            (assoc :session (-> session
                                (assoc :identity nil))))]
    response))

(defn web-create-account-handler [request]
  (->
   (try
     (let [fields (-> request :body slurp (json/read-str :key-fn keyword))
           email (:email fields)
           password (:password fields)
           project-id (or (:project-id fields)
                          (:project-id (project/get-default-project)))
           _ (assert (integer? project-id))
           success? (try (do (users/create-user
                              email password :project-id project-id)
                             true)
                         (catch Exception e
                           false))
           ;; now make sure email is sent successfully before storing user entry
           #_
           success?
           #_
           (if (not success?)
             false
             (try (let [entry (users/get-user-entry email)
                        _ (assert entry)
                        result
                        (mail/send-datapub-email
                         email
                         "Verify new Datapub account"
                         (str "An account was created for your email address at https://datapub.io.
If you created the account, follow this link to verify ownership: "
                              "https://datapub.io/verify/" (-> entry :verify-code)))]
                    (assert result)
                    (assert (= (:code result) 0))
                    true)
                  (catch Exception e
                    (try (users/delete-user email)
                         (catch Exception e
                           nil))
                    false)))]
       {:success success?
        :error (if success?
                 nil
                 "Error while creating user account")})
     (catch Exception e
       {:success false
        :error "Error while processing request"}))
   wrap-json))

(defn web-get-identity [request]
  (let [user (-> request :session :identity)
        response (if (and user (:email user) (:user-id user))
                   {:identity {:email (:email user)
                               :id (:user-id user)
                               :user-uuid (:user-uuid user)}}
                   {:identity nil})]
    (-> response wrap-json)))
