(ns sysrev.test.core
  (:require
   [clj-time.core :as time]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [prestancedesign.get-port :as get-port]
   [sysrev.config :refer [env]]
   [sysrev.db.core :as db]
   [sysrev.main :as main]
   [sysrev.user.interface :as user]
   [sysrev.util :as util :refer [in? shell]]
   [sysrev.web.core :as web]))

(def test-dbname "sysrev_auto_test")
(def test-db-host (get-in env [:postgres :host]))

(defn sysrev-handler [system]
  (web/sysrev-handler (:web-server system)))

(defn cpu-count []
  (.availableProcessors (Runtime/getRuntime)))

(defn get-default-threads []
  (max 4 (quot (cpu-count) 2)))

(defn get-selenium-config [system]
  (let [{:keys [protocol host port] :as config}
        (or (:selenium env)
            {:protocol "http"
             :host "localhost"
             :port (-> system :web-server :bound-port)})]
    (assoc config
           :url (str protocol "://" host (if port (str ":" port) "") "/"))))

(defn full-tests? []
  (let [{:keys [sysrev-full-tests profile]} env]
    (boolean
     (or (= profile :dev)
         (not (in? [nil "" "0" 0] sysrev-full-tests))))))

(defn test-profile? []
  (in? [:test :remote-test] (-> env :profile)))

(defn remote-test? []
  (= :remote-test (-> env :profile)))

(defn test-db-shell-args [& [postgres-overrides]]
  (let [{:keys [dbname user host port]}
        (merge (:postgres env) postgres-overrides)]
    ["-h" (str host) "-p" (str port) "-U" (str user) (str dbname)]))

(defn db-shell [cmd & [extra-args postgres-overrides]]
  (let [args (concat [cmd] (test-db-shell-args postgres-overrides) extra-args)]
    (log/info "db-shell:" (->> args (str/join " ") pr-str))
    (apply shell args)))

(defmacro with-test-system [[name-sym] & body]
  `(when-not (remote-test?)
     (binding [db/*active-db* (atom nil)
               db/*conn* nil
               db/*query-cache* (atom {})
               db/*query-cache-enabled* (atom true)
               db/*transaction-query-cache* nil]
       (let [postgres# (:postgres env)
             system# (main/start-non-global!
                      :config
                      {:datapub-embedded true
                       :server {:port (get-port/get-port)}}
                      :postgres-overrides
                      {:create-if-not-exists? true
                       :dbname (str test-dbname (rand-int Integer/MAX_VALUE))
                       :embedded? true
                       :host test-db-host
                       :port (get-port/get-port)}
                      :system-map-f
                      #(-> (apply main/system-map %&)
                           (dissoc :scheduler)))
             ~name-sym system#]
         (binding [env (merge env (:config system#))]
           (try
             (do ~@body)
             (finally
               (main/stop! system#))))))))

(defmacro completes? [form]
  `(do ~form true))

(defmacro succeeds? [form]
  `(try ~form (catch Throwable e# false)))

;; wait-until macro modified from
;; https://gist.github.com/kornysietsma/df45bbea3196adb5821b

(def default-timeout 10000)
(def default-interval 100)

(defn wait-until*
  ([name pred] (wait-until* name pred default-timeout default-interval))
  ([name pred timeout] (wait-until* name pred timeout default-interval))
  ([name pred timeout interval]
   (let [timeout (or timeout default-timeout)
         interval (or interval default-interval)
         die (time/plus (time/now) (time/millis timeout))]
     (loop [] (if-let [result (pred)] result
                      (do (Thread/sleep interval)
                          (if (time/after? (time/now) die)
                            (throw (ex-info (str "timed out waiting for " name)
                                            {:name name :timeout timeout :interval interval}))
                            (recur))))))))

(defmacro wait-until
  "Waits until function pred evaluates as true and returns result, or
  throws exception on timeout. timeout and interval may be passed as
  millisecond values, otherwise default values are used."
  ([pred] `(wait-until* ~(pr-str pred) ~pred))
  ([pred timeout] `(wait-until* ~(pr-str pred) ~pred ~timeout))
  ([pred timeout interval] `(wait-until* ~(pr-str pred) ~pred ~timeout ~interval)))

(defn create-test-user [& [{:keys [email password]
                            :or {email "browser+test@insilica.co"
                                 password (util/random-id)}}]]
  (let [[name domain] (str/split email #"@")
        email (format "%s+%s@%s" name (util/random-id) domain)]
    (assoc
     (user/create-user email password)
     :password password)))
