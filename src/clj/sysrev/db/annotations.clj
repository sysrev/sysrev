(ns sysrev.db.annotations
  (:require [sysrev.db.core :refer [do-query do-execute]]
            [honeysql.helpers :as sqlh :refer [insert-into values where select from delete-from sset]]
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

(defn associate-annotation-user [annotation-id user-id]
  (-> (insert-into :annotation_web_user)
      (values [{:annotation_id annotation-id
                :user_id user-id}])
      do-execute))

(defn user-defined-article-annotations [article-id]
  (let [annotations-articles (-> (select :*)
                                 (from :annotation_article)
                                 (where [:= :article-id
                                         article-id])
                                 do-query
                                 (->> (mapv :annotation-id)))]
    (if-not (empty? annotations-articles)
      (-> (select :*)
          (from :annotation)
          (where [:in :id annotations-articles])
          do-query)
      [])))

(defn delete-annotation!
  [annotation-id]
  (-> (delete-from :annotation)
      (where [:= :id annotation-id])
      do-execute))

(defn update-annotation!
  [annotation-id annotation]
  (-> (sqlh/update :annotation)
      (sset {:annotation annotation})
      (where [:= :id annotation-id])
      do-execute))
