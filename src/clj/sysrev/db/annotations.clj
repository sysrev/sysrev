(ns sysrev.db.annotations
  (:require [sysrev.db.core :refer [do-query do-execute]]
            [honeysql.helpers :refer [insert-into values where select from delete-from]]
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

(defn user-defined-article-annotations [article-id]
  (let [annotations-articles (-> (select :*)
                                 (from :annotation_article)
                                 (where [:= :article-id
                                         article-id])
                                 do-query
                                 (->> (mapv :annotation-id))
                                 )]
    (-> (select :*)
        (from :annotation)
        (where [:in :id annotations-articles])
        do-query)))

(defn delete-annotation!
  [annotation-id]
  (-> (delete-from :annotation)
      (where [:= :id annotation-id])
      do-execute))
