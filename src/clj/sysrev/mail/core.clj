(ns sysrev.mail.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [postal.core :as postal]
            [sysrev.config :refer [env]]
            [sysrev.db.queries :as q]
            [sysrev.user.interface :as user]))

(defn send-email [to-addr subject body]
  (if-not (:mock-email env)
    (let [config (:smtp env)]
      (postal/send-message
       config
       {:from (format "\"%s\" <%s>" (:name config) (:user config))
        :to [(str/lower-case to-addr)]
        :subject subject
        :body body}))
    (log/debug "Would have emailed: " to-addr)))

(defn send-password-reset-email [user-id & {:keys [url-base]
                                            :or {url-base "https://sysrev.com"}}]
  (let [email (q/get-user user-id :email)]
    (user/create-password-reset-code user-id)
    (send-email
     email "Sysrev Password Reset Requested"
     (with-out-str
       (printf "A password reset has been requested for email address %s on %s\n\n"
               email url-base)
       (printf "If you made this request, follow this link to reset your password: %s\n\n"
               (user/user-password-reset-url user-id :url-base url-base))))))
