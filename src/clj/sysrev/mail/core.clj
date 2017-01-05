(ns sysrev.mail.core
  (:require [postal.core :as postal]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn send-email [to-addr subject body]
  (let [config (-> (io/resource "smtp-config.edn") slurp read-string)]
    (postal/send-message
     config
     {:from (format "\"%s\" <%s>" (:name config) (:user config))
      :to [(str/lower-case to-addr)]
      :subject subject
      :body body})))
