(ns sysrev.slack
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.config :refer [env]]
            [sysrev.stacktrace :refer [print-cause-trace-custom cause-trace-custom-list]]
            [sysrev.util :as util :refer [pp-str]]))

;;;
;;; Get value from url at:
;;; https://api.slack.com/apps/AMLU7D7Q9/incoming-webhooks?success=1
;;;
(defonce slack-errors-token-override (atom nil))

(defn slack-errors-token []
  (or @slack-errors-token-override (env :sysrev-slack-errors)))

(defn log-slack [blocks-text notify-text & {:keys [force silent]}]
  (when-not silent
    (log/infof "Logging to Slack:\n* %s *\n%s"
               notify-text (str/join "\n" blocks-text)))
  (when (or force (= :prod (:profile env)))
    (when-let [token (slack-errors-token)]
      (let [msg {:text notify-text
                 :blocks (vec (for [text blocks-text]
                                {:type :section
                                 :text {:type "mrkdwn" :verbatim true :text text}}))}]
        (http/post (str "https://hooks.slack.com/services/" token)
                   {:body (json/write-str msg)
                    :content-type :application/json})))))

(defmacro try-log-slack [blocks-text notify-text]
  `(try (let [blocks-text# ~blocks-text
              notify-text# ~notify-text]
          (log-slack blocks-text# notify-text#))
        (catch Throwable e#
          (log/warnf "try-log-slack exception:\n%s"
                     (with-out-str (print-cause-trace-custom e#))))))

(defn request-info [req]
  (-> (merge {:host (get-in req [:headers "host"])
              :client-ip (get-in req [:headers "x-real-ip"])
              :all-params (some-> (:params req) keys vec)}
             (select-keys req [:uri :compojure/route :query-params])
             (when-let [ident (not-empty (-> req :session :identity))]
               {:session {:identity (select-keys ident [:user-id :email])}}))
      (dissoc :sr-context)))

(defn- log-slack-request-exception [request ^Throwable e]
  (try (log-slack
        (concat [(format "*Request*:\n```%s```" (pp-str (request-info request)))
                 (format "*Stacktrace*:")]
                (for [st (cause-trace-custom-list e 30)]
                  (format "```%s```" st)))
        (str (if-let [route (:compojure/route request)]
               (str route " => ") "")
             (.getMessage e))
        :silent true)
       (catch Throwable e2
         (log/error "error in log-slack-request-exception:\n"
                    (with-out-str (print-cause-trace-custom e2)))
         (try (let [info {:request (select-keys request [:server-name :compojure/route])}]
                (log-slack (concat [(format "*Slack Message Error*\n```%s```" (pp-str info))
                                    (format "*Stacktrace*:")]
                                   (for [st (cause-trace-custom-list e 30)]
                                     (format "```%s```" st)))
                           "Slack Message Error"
                           :silent true))
              (catch Throwable e3
                (util/ignore-exceptions
                 (log/error "Unexpected Slack Message Error\n"
                            (with-out-str (print-cause-trace-custom e3)))
                 (log-slack ["*Unexpected Slack Message Error*"]
                            "Unexpected Slack Message Error")))))))

(defn log-request-exception [request e]
  (try (log/error "Request:\n" (pp-str (request-info request))
                  "Exception:\n" (with-out-str (print-cause-trace-custom e)))
       (log-slack-request-exception request e)
       (catch Throwable e2
         (log/error "error in log-request-exception:\n"
                    (with-out-str (print-cause-trace-custom e2))))))
