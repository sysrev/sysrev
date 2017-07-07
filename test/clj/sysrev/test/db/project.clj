(ns sysrev.test.db.project
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as p]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.db.core :refer [test-project-ids]]))

(use-fixtures :once default-fixture)

(deftest article-flag-counts
  (doseq [project-id (test-project-ids)]
    (let [query (q/select-project-articles
                 project-id [:%count.*] {:include-disabled? true})
          total (-> query do-query first :count)
          flag-enabled (-> query (q/filter-article-by-disable-flag true)
                           do-query first :count)
          flag-disabled (-> query (q/filter-article-by-disable-flag false)
                            do-query first :count)]
      (is (= total (+ flag-enabled flag-disabled))))))
