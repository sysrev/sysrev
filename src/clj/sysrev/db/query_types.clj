(ns sysrev.db.query-types
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :as sqlh-pg :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as sutil :refer [apply-keyargs]]))

(defn find-label [match-by fields & {:keys [include-disabled] :as opts}]
  (apply-keyargs q/find [:label :l]
                 (cond-> match-by
                   (not include-disabled) (merge {:enabled true}))
                 fields
                 (dissoc opts :include-disabled)))

(defn find-one-label [match-by fields & {:keys [include-disabled] :as opts}]
  (apply-keyargs q/find-one [:label :l]
                 (cond-> match-by
                   (not include-disabled) (merge {:enabled true}))
                 fields
                 (dissoc opts :include-disabled)))
