(ns sysrev.slack
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.config :refer [env]]
            [sysrev.stacktrace :refer [print-cause-trace-custom]]
            [sysrev.util :as util :refer [pp-str]]))

;;;
;;; Get value from url at:
;;; https://api.slack.com/apps/AMLU7D7Q9/incoming-webhooks?success=1
;;;
(defonce slack-errors-token-override (atom nil))

(defn slack-errors-token []
  (or @slack-errors-token-override (env :sysrev-slack-errors)))

(defn log-slack [blocks-text notify-text]
  (when-let [token (slack-errors-token)]
    (let [msg {:text notify-text
               :blocks (vec (for [text blocks-text]
                              {:type :section
                               :text {:type "mrkdwn" :verbatim true :text text}}))}]
      (http/post (str "https://hooks.slack.com/services/" token)
                 {:body (json/write-str msg)
                  :content-type :application/json}))))

(defn try-log-slack [blocks-text notify-text]
  (try (log-slack blocks-text notify-text)
       (catch Exception e
         (log/warnf "log-slack exception: %s"
                    (with-out-str (print-cause-trace-custom e 20))))))

(defn log-slack-custom [blocks-text notify-text & {:keys [force]}]
  (when (or force (= :prod (:profile env)))
    (log/infof "Logging to Slack:\n* %s *\n%s"
               notify-text (str/join "\n" blocks-text))
    (log-slack blocks-text notify-text)))

(defn request-info [req]
  (-> (merge {:host (get-in req [:headers "host"])
              :client-ip (get-in req [:headers "x-real-ip"])}
             (select-keys req [:uri :compojure/route :query-params])
             (when-let [ident (not-empty (-> req :session :identity))]
               {:session {:identity (select-keys ident [:user-id :email])}}))
      (dissoc :web-server)))

(defn log-slack-request-exception [request ^Throwable e & {:keys [force]}]
  (when (or force (= :prod (:profile env)))
    (try (log-slack
          [(format "*Request*:\n```%s```"
                   (pp-str (request-info request)))
           (format "*Exception*:\n```%s```"
                   (with-out-str (print-cause-trace-custom e 20)))]
          (str (if-let [route (:compojure/route request)]
                 (str route " => ") "")
               (.getMessage e)))
         (catch Exception e2
           (log/error "error in log-slack-request-exception:\n"
                      (with-out-str (print-cause-trace-custom e2)))
           (try (let [info {:request (select-keys request [:server-name :compojure/route])}]
                  (log-slack [(format "*Slack Message Error*\n```%s```" (pp-str info))]
                             "Slack Message Error"))
                (catch Exception _
                  (log-slack ["*Unexpected Slack Message Error*"]
                             "Unexpected Slack Message Error")))))))

(defn log-request-exception [request e]
  (try (log/error "Request:\n" (pp-str (request-info request))
                  "Exception:\n" (with-out-str (print-cause-trace-custom e)))
       (log-slack-request-exception request e)
       (catch Exception e2
         (log/error "error in log-request-exception:\n"
                    (with-out-str (print-cause-trace-custom e2))))))
