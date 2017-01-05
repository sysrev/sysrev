(ns sysrev.test.db
  (:require [clojure.test :refer :all]
            [sysrev.user :refer [started]]
            [sysrev.test.core :refer [completes?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.articles :as articles]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.connect :refer [config-fixture]]))

(use-fixtures :once config-fixture)

(deftest db-connected
  (is (completes?
       (-> (select :%count.*)
           (from :label)
           do-query))))
