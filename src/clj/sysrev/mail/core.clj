(ns sysrev.mail.core
  (:require [postal.core :as postal]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sysrev.config :refer [env]]))

(defn send-email [to-addr subject body]
  (if-not (:mock-email env)
    (let [config (-> (io/resource "smtp-config.edn") slurp read-string)]
      (postal/send-message
       config
       {:from (format "\"%s\" <%s>" (:name config) (:user config))
        :to [(str/lower-case to-addr)]
        :subject subject
        :body body}))
    (log/debug "Would have emailed: " to-addr)))
