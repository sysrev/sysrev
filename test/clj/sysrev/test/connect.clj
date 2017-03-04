(ns sysrev.test.connect
  (:require [clojure.test :refer :all]
            [clojure.spec :as s]
            [clojure.spec.test :as t]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.db.core :refer [do-query do-execute]]))

(use-fixtures :once default-fixture)

(deftest connected
  (is (completes?
       (-> (select :%now)
           do-query))))

(deftest connected-2
  (is (completes?
       (-> (select :%count.*)
           (from :label)
           do-query))))
