(ns sysrev.db.articles
  (:require [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn all-criteria []
  (-> (select :*)
      (from :criteria)
      (order-by :criteria_id)
      do-query))

(defn sorted-articles []
  (-> (select :*)
      (from [:article :a])
      (join [:article_ranking :r] [:= :a.article_id :r._1])
      (order-by :_2)
      do-query))
