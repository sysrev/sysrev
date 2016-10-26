(ns sysrev.connect
  (:require [clojure.test :refer :all]
            [config.core :refer [env]]
            [honeysql.core :as sql]
            [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))


(defn config-fixture [f]
  (let [{{postgres-port :port
          dbname :dbname} :postgres
         profile :profile} env
        validators [["Test profile must be loaded"
                       #(= (profile :test))]
                    ["Cannot run tests with pg on port 5432"
                       #(not (= postgres-port 5432))]
                    ["Db name must include _test in configuration"
                       #(clojure.string/includes? dbname "_test")]]
        error (->> validators (remove #(-> % second (apply []))) first first)]
    (if error
      (println error)
      (f))))

(use-fixtures :once config-fixture)


(deftest connected
  (-> (select :%now)
      do-query))
