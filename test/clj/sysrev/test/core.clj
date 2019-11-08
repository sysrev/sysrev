(ns sysrev.test.core
  (:require [orchestra.spec.test :as t]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [sysrev.config.core :refer [env]]
            [sysrev.init :refer [start-app]]
            [sysrev.web.index :refer [set-web-asset-path]]
            [sysrev.db.core :as db]
            [sysrev.db.migration :refer [ensure-updated-db]]
            [sysrev.label.core :as labels]
            [sysrev.util :as util :refer [shell]]
            [sysrev.shared.util :as sutil :refer [in?]])
  (:import [java.util UUID]))

(def test-dbname "sysrev_auto_test")
(def test-db-host (get-in env [:postgres :host]))

(defonce raw-selenium-config (atom (-> env :selenium)))

(defonce db-initialized? (atom nil))

(defn db-connected? []
  (and (not= "sysrev.com" (:host @raw-selenium-config))
       (not= 5470 (-> env :postgres :port))))

(defn get-selenium-config []
  (let [{:keys [protocol host port] :as config}
        (or @raw-selenium-config {:protocol "http"
                                  :host "localhost"
                                  :port (-> env :server :port)})]
    (assoc config
           :url (str protocol "://" host (if port (str ":" port) "") "/")
           :blog-url (if (and port (= host "localhost"))
                       (str protocol "://" host ":" (inc port) "/")
                       (let [host (str "blog." host)]
                         (str protocol "://" host (if port (str ":" port) "") "/")))
           :safe (db-connected?))))

(defn ^:repl set-selenium-config [raw-config]
  (reset! raw-selenium-config raw-config))

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

(defn write-flyway-config [& [postgres-overrides]]
  (let [{:keys [dbname user host port]}
        (merge (:postgres env) postgres-overrides)]
    (shell "mv" "-f" "flyway.conf" ".flyway.conf.moved")
    (spit "flyway.conf"
          (->> [(format "flyway.url=jdbc:postgresql://%s:%d/%s"
                        host port dbname)
                (format "flyway.user=%s" user)
                "flyway.password="
                "flyway.locations=filesystem:./resources/sql"
                ""]
               (str/join "\n")))))

(defn restore-flyway-config []
  (shell "mv" "-f" ".flyway.conf.moved" "flyway.conf"))

(defmacro with-flyway-config [postgres-overrides & body]
  `(try
     (write-flyway-config ~postgres-overrides)
     ~@body
     (finally
       (restore-flyway-config))))

(defn init-test-db []
  (when (db-connected?)
    (let [config {:dbname test-dbname :host test-db-host}]
      (if @db-initialized?
        (do (start-app config nil true)
            (let [{:keys [host port dbname]} (:config @db/active-db)]
              (log/infof "connected to postgres (%s:%d/%s)" host port dbname)))
        (util/with-print-time-elapsed "Initialize test DB"
          (log/info "Initializing test DB...")
          (db/close-active-db)
          (db/terminate-db-connections config)
          (try (db-shell "dropdb" [] config)
               (catch Throwable _ nil))
          (db-shell "createdb" [] config)
          (log/info "Applying Flyway schema...")
          (when-not (util/ms-windows?)
            (shell "./scripts/install-flyway"))
          (with-flyway-config config
            (log/info (str "\n" (slurp "flyway.conf")))
            (-> (if (util/ms-windows?)
                  (shell ".flyway-5.2.4/flyway.cmd" "migrate")
                  (shell "./flyway" "migrate"))
                :out log/info))
          (start-app config nil true)
          (log/info "Applying Clojure DB migrations...")
          (ensure-updated-db)
          (reset! db-initialized? true))))))

(defonce db-shutdown-hook (atom nil))

(defn close-db-resources []
  (when (db-connected?)
    (db/close-active-db)
    (db/terminate-db-connections {:dbname test-dbname})))

(defn ensure-db-shutdown-hook
  "Ensures that any db connections are closed when JVM exits."
  []
  (when-not @db-shutdown-hook
    (.addShutdownHook (Runtime/getRuntime) (Thread. close-db-resources))
    (reset! db-shutdown-hook true)))

(defn default-fixture
  "Basic setup for all tests (db, web server, clojure.spec)."
  [f]
  (case (:profile env)
    :test
    (do (ensure-db-shutdown-hook)
        (t/instrument)
        (set-web-asset-path "/out-production")
        (if (db-connected?)
          (init-test-db)
          (db/close-active-db))
        (f))
    :remote-test
    (let [{{postgres-port :port dbname :dbname}     :postgres
           {selenium-host :host} :selenium} env]
      (when (or (= selenium-host "sysrev.com")
                (= postgres-port 5470))
        (assert (str/includes? dbname "_test")
                "Connecting to 'sysrev' db on production server is not allowed"))
      (ensure-db-shutdown-hook)
      (t/instrument)
      (if (db-connected?)
        (db/set-active-db! (db/make-db-config (:postgres env)) true)
        (db/close-active-db))
      (f))
    :dev
    (do (t/instrument)
        (set-web-asset-path "/out")
        (if (db-connected?)
          (init-test-db)
          (db/close-active-db))
        (f))
    (assert false "default-fixture: invalid profile value")))

;; note: If there is a field (e.g. id) that is auto-incremented
;; when new entries are made, even wrapping the call in a
;; rollback will result in the number increasing.
;; e.g. project-id will be 615, next time 616. For a discussion
;; for why this is, see
;; https://stackoverflow.com/questions/449346/mysql-auto-increment-does-not-rollback
;; https://www.postgresql.org/message-id/501B1494.9040502@ringerc.id.au
(defn database-rollback-fixture [test-fn]
  (db/with-rollback-transaction
    (test-fn)))

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

(defn add-test-label [project-id entry-values]
  (let [{:keys [value-type short-label]} entry-values
        add-label (case value-type
                    "boolean" labels/add-label-entry-boolean
                    "categorical" labels/add-label-entry-categorical
                    "string" labels/add-label-entry-string)]
    (->> (merge entry-values {:name (str short-label "_" (rand-int 1000))})
         (add-label project-id))))
