(ns sysrev.sendgrid
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [sysrev.config :refer [env]]))

(def sendgrid-api-key (env :sendgrid-api-key))
(def sendgrid-api-url "https://api.sendgrid.com/v3/")
(def sendgrid-default-from "info@sysrev.com")
(def sendgrid-default-template-id "536e9128-efde-4d0c-a9bc-56fa1a4b19e6")
(def sendgrid-asm-group-id 7963)
;; some code from
;; https://github.com/Purple-Services/common/blob/dev/src/common/sendgrid.clj
;; Copyright 2017 Purple Services Inc
;; MIT License: https://github.com/Purple-Services/common/blob/dev/LICENSE

(def common-opts
  {:as :json
   :content-type :json
   :coerce :always
   :throw-exceptions false
   :headers {"Authorization" (str "Bearer " sendgrid-api-key)}})

(defn- request
  [endpoint & [params headers]]
  (let [{:keys [errors] :as body}
        #__ (-> (http/post (str sendgrid-api-url endpoint)
                           (merge-with merge
                                       common-opts
                                       {:form-params params}
                                       {:headers headers}))
                :body)]
    (when (seq errors)
      (throw (ex-info "Error sending email"
                      {:endpoint endpoint
                       :headers headers
                       :errors errors
                       :params params})))
    {:success true
     :resp body}))

(defn- send-email
  [to from subject payload & {:keys [cc]}]
  (let [request-params (merge {:personalizations [{:to [{:email to}]
                                                          :cc (when (seq cc) [{:email cc}])
                                                          :subject subject}]
                                      :from {:email from
                                             :name "SysRev"}
                                      :asm {:group_id sendgrid-asm-group-id}}
                                     payload)]
    (if (:mock-email env)
      (do
        (log/debug "Would have emailed:" request-params)
        {:success true})
      (request "mail/send" request-params))))

(defn send-html-email
  [to subject message
   & {:keys [from cc]
      :or {from sendgrid-default-from}}]
  (send-email to from subject
              {:content [{:type "text/html" :value message}]}
              :cc cc))

(defn send-template-email
  [to subject message
   & {:keys [from template-id]
      :or {from sendgrid-default-from
           template-id sendgrid-default-template-id}}]
  (send-email to from subject
              {:content [{:type "text/html" :value message}]
               :template_id template-id}))
