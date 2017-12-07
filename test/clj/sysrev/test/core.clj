(ns sysrev.test.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [sysrev.config.core :refer [env]]
            [sysrev.init :refer [start-app]]
            [sysrev.web.core :refer [stop-web-server]]
            [sysrev.web.index :refer [set-web-asset-path]]
            [sysrev.db.core :refer [set-active-db! make-db-config close-active-db
                                    clear-query-cache]]))

(defonce raw-selenium-config (atom (-> env :selenium)))

(defn set-selenium-config [raw-config]
  (reset! raw-selenium-config raw-config))

(defn get-selenium-config []
  (let [config (or @raw-selenium-config
                   {:protocol "http"
                    :host "localhost"
                    :port (-> env :server :port)})]
    (let [{:keys [protocol host port]} config]
      (assoc config
             :url (str protocol "://" host (if port (str ":" port) "") "/")
             :safe (not= host "sysrev.us")))))

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
      (set-web-asset-path "/integration")
      (start-app nil nil true)
      (f)
      (stop-web-server)
      (close-active-db))
    :remote-test
    (let [{{postgres-port :port
            dbname :dbname} :postgres
           {selenium-host :host
            protocol :protocol} :selenium} env]
      (when (or (= selenium-host "sysrev.us")
                (= postgres-port 5470))
        (assert (clojure.string/includes? dbname "_test")
                "Connecting to 'sysrev' db on production server is not allowed"))
      (t/instrument)
      (set-active-db! (make-db-config (:postgres env)) true)
      (f)
      (close-active-db))
    :dev
    (do (t/instrument)
        (set-web-asset-path "/integration")
        (start-app {:dbname "sysrev_test"} nil true)
        (f)
        (close-active-db))
    (assert false "default-fixture: invalid profile value")))

(defmacro completes? [form]
  `(do ~form true))
