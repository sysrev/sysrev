(ns sysrev.mail.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [postal.core :as postal]
            [sysrev.config :refer [env]]))

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
