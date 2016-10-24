(ns sysrev.connect
  (:require [clojure.test :refer :all]
            [config.core :refer [env]]
            [honeysql.core :as sql]
            [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))


(defn config-fixture [f]
  (let [{{postgres-port :port} :postgres
         profile :profile} env]
    (if (= profile :test)
      (if (not (= postgres-port 5432))
        (f)
        (println "Cannot run test with pg on port 5432"))
      (println ":test profile not loaded"))))

(use-fixtures :once config-fixture)


(deftest connected
  (-> (select :%now)
      do-query))
