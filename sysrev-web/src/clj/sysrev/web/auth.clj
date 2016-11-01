(ns sysrev.web.auth
  (:require [clojure.data.json :as json]
            [ring.util.response :as r]
            [sysrev.db.users :as users]
            [sysrev.web.ajax :refer [wrap-json get-user-id]]))

(defn web-login-handler [request]
  (let [session (:session request)
        fields (-> request :body slurp (json/read-str :key-fn keyword))
        user (users/get-user-by-email (:email fields))
        auth-session (assoc session
                            :identity (:email user)
                            :user-id (:user-id user))
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
        response (-> result
                     wrap-json
                     (assoc :session (-> session
                                         (assoc :identity nil)
                                         (dissoc :user-id))))]
    response))

(defn web-create-account-handler [request]
  (let [fields (-> request :body slurp (json/read-str :key-fn keyword))
        email (:email fields)
        password (:password fields)
        project-id (:project-id fields)
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
    (-> {:success success?} wrap-json)))

(defn web-get-identity [request]
  (let [user-id (get-user-id request)
        email (-> request :session :identity)
        response (if (and email user-id)
                   {:identity {:email email :id user-id}}
                   {:identity nil})]
    (-> response wrap-json)))
