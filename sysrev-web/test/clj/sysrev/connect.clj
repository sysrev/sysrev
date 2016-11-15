(ns sysrev.connect
  (:require [clojure.test :refer :all]
            [config.core :refer [env]]
            [honeysql.core :as sql]
            [sysrev.db.core :refer [do-query do-execute]]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [clojure.tools.logging :as log]))

(defn config-fixture
  "Validates configuration, tries to ensure we're running
   the tests on a test database"
  [f]
  (let [{{postgres-port :port
          dbname :dbname} :postgres
         profile :profile} env
        validators [["Test profile must be loaded"
                       #(= profile :test)]
                    ["Cannot run tests with pg on port 5432"
                       #(not (= postgres-port 5432))]
                    ["Db name must include _test in configuration"
                       #(clojure.string/includes? dbname "_test")]]
        validates #(-> % second (apply []))
        error (->> validators (remove validates) first first)]
    (if error
      (log/error "config-fixture: " error)
      (f))))


(use-fixtures :once config-fixture)


(deftest connected
  (-> (select :%now)
      do-query))
