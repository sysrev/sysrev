(ns sysrev.test.core
  (:require [clojure.test :refer :all]
            [clojure.spec :as s]
            [clojure.spec.test :as t]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [sysrev.init :refer [start-app]]
            [sysrev.web.index :refer [set-web-asset-path]]))

(defn default-fixture
  "Validates configuration, tries to ensure we're running
   the tests on a test database"
  [f]
  (case (:profile env)
    :test
    (let [{{postgres-port :port
            dbname :dbname} :postgres
           profile :profile} env
          validators [#_ ["Test profile must be loaded"
                          #(= profile :test)]
                      #_ ["Cannot run tests with pg on port 5432"
                          #(not (= postgres-port 5432))]
                      ["Db name must include _test in configuration"
                       #(clojure.string/includes? dbname "_test")]]
          validates #(-> % second (apply []))
          error (->> validators (remove validates) first first)]
      (if error
        (log/error "default-fixture: " error)
        (do (t/instrument)
            (set-web-asset-path "/integration")
            (start-app)
            (f))))
    :dev
    (do (t/instrument)
        (set-web-asset-path "/integration")
        (start-app {:dbname "sysrev_test"})
        (f))
    (log/error "default-fixture: profile must be :test or :dev")))

(defmacro completes? [form]
  `(do ~form true))
