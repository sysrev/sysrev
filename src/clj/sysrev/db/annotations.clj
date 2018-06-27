(ns sysrev.db.annotations
  (:require [sysrev.db.core :refer [do-query do-execute]]
            [honeysql.helpers :refer [insert-into values]]
            [honeysql-postgres.helpers :refer [returning]]))

(defn create-annotation [selection annotation]
  (-> (insert-into :annotation)
      (values [{:selection selection
                :annotation annotation}])
      (returning :id)
      do-query
      first
      :id))

(defn associate-annotation-article [annotation-id article-id]
  (-> (insert-into :annotation_article)
      (values [{:annotation_id annotation-id
                :article_id article-id}])
      do-execute))
