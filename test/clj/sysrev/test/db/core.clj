(ns sysrev.test.db.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.db.core :refer [do-query do-execute]]))

(use-fixtures :once default-fixture)

(defn test-project-ids []
  (-> (select :project-id)
      (from :project)
      (->> do-query (mapv :project-id))))
