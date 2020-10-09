(ns sysrev.sendgrid
  (:require [clj-http.client :as http]
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
  (try (let [resp (-> (http/post (str sendgrid-api-url endpoint)
                                 (merge-with merge
                                             common-opts
                                             {:form-params params}
                                             {:headers headers}))
                      :body)]
         {:success (empty? (:errors resp))
          :resp resp})
       (catch Throwable _
         {:success false
          :resp {:error {:message "Unknown error."}}})))

;; Example usage of :substitutions key
;; {:%name% "Jerry Seinfield"
;;  :%planName% "Standard Plan"}
;; If, in your sendgrid template, you had e.g., "Hi %name%"
(defn- send-email
  [to from subject payload & {:keys [substitutions]}]
  (request "mail/send"
           (merge {:personalizations [{:to [{:email to}]
                                       :subject subject
                                       :substitutions substitutions}]
                   :from {:email from
                          :name "SysRev"}
                   :asm {:group_id sendgrid-asm-group-id}}
                  payload)))

(defn ^:unused send-text-email
  [to subject message
   & {:keys [from]
      :or {from sendgrid-default-from}}]
  (send-email to from subject
              {:content [{:type "text" :value message}]}))

(defn ^:unused send-html-email
  [to subject message
   & {:keys [from]
      :or {from sendgrid-default-from}}]
  (send-email to from subject
              {:content [{:type "text/html" :value message}]}))

(defn send-template-email
  [to subject message
   & {:keys [from template-id substitutions]
      :or {from sendgrid-default-from
           template-id sendgrid-default-template-id}}]
  (send-email to from subject
              {:content [{:type "text/html" :value message}]
               :template_id template-id}
              :substitutions substitutions))
