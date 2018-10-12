(ns sysrev.test.core
  (:import [java.util UUID])
  (:require [amazonica.aws.s3 :as s3]
            [clj-time.core :as time]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [sysrev.config.core :refer [env]]
            [sysrev.init :refer [start-app]]
            [sysrev.web.core :refer [stop-web-server]]
            [sysrev.web.index :refer [set-web-asset-path]]
            [sysrev.db.core :as db]
            [sysrev.db.users :as users]
            [sysrev.db.labels :as labels]
            [sysrev.db.migration :as migrate]
            [sysrev.stripe :as stripe]
            [sysrev.util :as util :refer [shell]]
            [sysrev.shared.util :as sutil :refer [in?]]))

(def test-dbname "sysrev_auto_test")

(defonce raw-selenium-config (atom (-> env :selenium)))

(defonce db-initialized? (atom nil))

(defn db-connected? []
  (and (not= "sysrev.com" (:host @raw-selenium-config))
       (not= 5470 (-> env :postgres :port))))

(defn get-selenium-config []
  (let [config (or @raw-selenium-config
                   {:protocol "http"
                    :host "localhost"
                    :port (-> env :server :port)})]
    (let [{:keys [protocol host port]} config]
      (assoc config
             :url (str protocol "://" host (if port (str ":" port) "") "/")
             :safe (db-connected?)))))

(defn set-selenium-config [raw-config]
  (reset! raw-selenium-config raw-config))

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
    (let [config {:dbname test-dbname}]
      (if @db-initialized?
        (do (start-app config nil true)
            (let [{:keys [host port dbname]}
                  (-> @db/active-db :config)]
              (log/info (format "connected to postgres (%s:%d/%s)"
                                host port dbname))))
        (do (log/info "Initializing test DB...")
            (db/close-active-db)
            (db/terminate-db-connections config)
            (try
              (db-shell "dropdb" [] config)
              (catch Throwable e
                nil))
            (db-shell "createdb" [] config)
            (log/info "Applying Flyway schema...")
            (shell "./scripts/install-flyway")
            (with-flyway-config config
              (log/info (str "\n" (slurp "flyway.conf")))
              (-> (shell "./flyway" "migrate")
                  :out log/info))
            (start-app config nil true)
            (log/info "Applying Clojure DB migrations...")
            (migrate/ensure-updated-db)
            (reset! db-initialized? true)
            (log/info "Test DB ready"))))))

(defn default-fixture
  "Validates configuration, tries to ensure we're running
   the tests on a test database"
  [f]
  (case (:profile env)
    :test
    (let [{{postgres-port :port
            dbname :dbname} :postgres} env
          validators [#_ ["Cannot run tests with pg on port 5432"
                          #(not (= postgres-port 5432))]
                      ["Db name must include _test in configuration"
                       #(clojure.string/includes? dbname "_test")]]
          validates #(-> % second (apply []))
          error (->> validators (remove validates) first first)]
      (assert (not error) error)
      (t/instrument)
      (set-web-asset-path "/out-production")
      #_ (start-app nil nil true)
      (if (db-connected?)
        (init-test-db)
        (db/close-active-db))
      (f)
      (when (db-connected?)
        (db/close-active-db)
        (db/terminate-db-connections {:dbname test-dbname})))
    :remote-test
    (let [{{postgres-port :port
            dbname :dbname} :postgres
           {selenium-host :host
            protocol :protocol} :selenium} env]
      (when (or (= selenium-host "sysrev.com")
                (= postgres-port 5470))
        (assert (clojure.string/includes? dbname "_test")
                "Connecting to 'sysrev' db on production server is not allowed"))
      (t/instrument)
      (if (db-connected?)
        (db/set-active-db! (db/make-db-config (:postgres env)) true)
        (db/close-active-db))
      (f)
      (db/close-active-db))
    :dev
    (do (t/instrument)
        (set-web-asset-path "/out")
        #_ (start-app {:dbname "sysrev_test"} nil true)
        (if (db-connected?)
          (init-test-db)
          (db/close-active-db))
        (f)
        (when (db-connected?)
          (db/close-active-db)
          (db/terminate-db-connections {:dbname test-dbname})))
    (assert false "default-fixture: invalid profile value")))

;; note: If there is a field (e.g. id) that is auto-incremented
;; when new entries are made, even wrapping the call in a
;; rollback will result in the number increasing.
;; e.g. project-id will be 615, next time 616. For a discussion
;; for why this is, see
;; https://stackoverflow.com/questions/449346/mysql-auto-increment-does-not-rollback
;; https://www.postgresql.org/message-id/501B1494.9040502@ringerc.id.au
(defn database-rollback-fixture [test]
  (db/with-rollback-transaction
    (test)))

(defmacro completes? [form]
  `(do ~form true))

(defn filestore-fixture [test]
  (let [bucket-name (str (UUID/randomUUID))]
    (binding [env (assoc-in env [:filestore :bucket-name] bucket-name)]
      (s3/create-bucket bucket-name)
      (test)
      (s3/delete-bucket bucket-name))))

;; wait-until macro modified from
;; https://gist.github.com/kornysietsma/df45bbea3196adb5821b

(def default-timeout (time/seconds 10))
(def default-wait-delay-ms 100)

(defn wait-until*
  ([name pred] (wait-until* name pred default-timeout))
  ([name pred timeout]
   (let [die (time/plus (time/now) timeout)]
     (loop []
       (if-let [result (pred)]
         result
         (do
           (Thread/sleep default-wait-delay-ms)
           (if (time/after? (time/now) die)
             (throw (Exception. (str "timed out waiting for: " name)))
             (recur))))))))

(defmacro wait-until
  "wait until pred has become true with optional :timeout"
  [pred]
  `(wait-until* ~(pr-str pred) ~pred))

(defn delete-user-fixture
  [email]
  (fn [test]
    (do (test)
        (let [user (users/get-user-by-email email)
              user-id (:user-id user)]
          (when (int? user-id)
            (users/delete-user (:user-id user))
            (is (:deleted (stripe/delete-customer! user)))
            ;; make sure this has occurred for the next test
            (wait-until #(nil? (users/get-user-by-email email)))
            (is (nil? (users/get-user-by-email email))))))))

(defn full-tests? []
  (let [{:keys [sysrev-full-tests profile]} env]
    (boolean
     (or (= profile :dev)
         (not (in? [nil "" "0" 0] sysrev-full-tests))))))

(defn test-profile? []
  (in? [:test :remote-test] (-> env :profile)))

(defn remote-test? []
  (= :remote-test (-> env :profile)))

(defn add-test-label [project-id entry-values]
  (let [add-label (case (:value-type entry-values)
                    "boolean" labels/add-label-entry-boolean
                    "categorical" labels/add-label-entry-categorical
                    "string" labels/add-label-entry-string)]
    (add-label project-id (merge entry-values
                                 {:name (str (:short-label entry-values)
                                             "_" (rand-int 1000))}))))
