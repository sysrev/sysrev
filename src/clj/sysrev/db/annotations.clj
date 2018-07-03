(ns sysrev.db.annotations
  (:require [honeysql.helpers :as sqlh :refer [insert-into values where select from delete-from sset join]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute to-jsonb]]))

(defn create-semantic-class!
  [definition]
  (-> (insert-into :semantic_class)
      (values [{:definition (to-jsonb definition)}])
      (returning :id)
      do-query
      first
      :id))

(defn associate-annotation-semantic-class! [annotation-id semantic-class-id]
  (-> (insert-into :annotation_semantic_class)
      (values [{:annotation_id annotation-id
                :semantic_class_id semantic-class-id}])
      (returning :*)
      do-execute
      first))

(defn create-annotation! [selection annotation]
  (-> (insert-into :annotation)
      (values [{:selection selection
                :annotation annotation}])
      (returning :id)
      do-query
      first
      :id))

(defn associate-annotation-article! [annotation-id article-id]
  (-> (insert-into :annotation_article)
      (values [{:annotation_id annotation-id
                :article_id article-id}])
      do-execute))

(defn annotation-semantic-class [annotation-id]
  "Get the semantic-class associated with annotation-id"
  (let [semantic-class-id
        (-> (select :*)
            (from :annotation_semantic_class)
            (where [:= :annotation-id annotation-id])
            do-query
            first
            :semantic-class-id)]
    (-> (select :definition)
        (from :semantic_class)
        (where [:= :id semantic-class-id])
        do-query
        first
        :definition)))

(defn dissociate-semantic-class! [annotation-id semantic-class-id]
  (-> (delete-from :annotation_semantic_class)
      (where [:and
              [:= :annotation_id annotation-id]
              [:= :semantic_class_id semantic-class-id]])
      do-execute))

(defn associate-annotation-user! [annotation-id user-id]
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
      (let [annotations (-> (select :*)
                            (from :annotation)
                            (where [:in :id annotations-articles])
                            do-query)]
        (map #(assoc % :semantic-class (annotation-semantic-class (:id %)))
             annotations))
      [])))

(defn delete-annotation!
  [annotation-id]
  (-> (delete-from :annotation)
      (where [:= :id annotation-id])
      do-execute))

(defn annotation-id->project-id
  [annotation-id]
  (let [article-id (-> (select :article-id)
                       (from :annotation_article)
                       (where [:= :annotation_id annotation-id])
                       do-query
                       first
                       :article-id)]
    (-> (select :project-id)
        (from :article)
        (where [:= :article_id article-id])
        do-query
        first
        :project-id)))

(defn project-active-semantic-classes
  "Get the semantic classes associated annotations that are active"
  [project-id]
  (let [maybe-empty (fn [coll]
                      (if (empty? coll)
                        '(nil)
                        coll))
        article-ids (map :article-id (-> (select :aa.article-id)
                                         (from [:annotation_article :aa])
                                         (join [:article :a] [:= :aa.article-id :a.article-id])
                                         (where [:= :a.project-id 728])
                                         do-query))
        annotation-ids (map :annotation-id (-> (select :annotation-id)
                                               (from :annotation_article)
                                               (where [:in :article_id (maybe-empty article-ids)])
                                               do-query))
        semantic-class-ids (map :semantic-class-id
                                (-> (select :semantic-class-id)
                                    (from :annotation_semantic_class)
                                    (where [:in :annotation_id (maybe-empty annotation-ids)])
                                    do-query))
        semantic-classes (-> (select :*)
                             (from :semantic_class)
                             (where [:in :id (maybe-empty semantic-class-ids)])
                             do-query)]
    semantic-classes))

(defn update-annotation!
  "Update an annotation, with an optional semantic-class. If semantic-class is empty (blank string) or nil, a semantic-class of null will be associated with the annotation"
  [annotation-id annotation & [semantic-class]]
  ;; update the annotation
  (-> (sqlh/update :annotation)
      (sset {:annotation annotation})
      (where [:= :id annotation-id])
      do-execute)
  ;; update the semantic-class
  (let [semantic-class (if (empty? semantic-class)
                         nil
                         semantic-class)
        current-semantic-class-id (-> (select :semantic_class_id)
                                      (from :annotation_semantic_class)
                                      (where [:= :annotation-id annotation-id])
                                      do-query
                                      first
                                      :semantic-class-id)
        current-semantic-class (-> (select :*)
                                   (from :semantic_class)
                                   (where [:= :id current-semantic-class-id])
                                   do-query
                                   first)
        new-semantic-class (-> (select :*)
                               (from :semantic_class)
                               (where [:= :definition (to-jsonb semantic-class)])
                               do-query
                               first)]
    ;; create a new semantic-class, if needed
    (if (nil? new-semantic-class)
      (let [new-semantic-class-id (create-semantic-class! semantic-class)]
        (associate-annotation-semantic-class! annotation-id new-semantic-class-id))
      (associate-annotation-semantic-class! annotation-id (:id new-semantic-class)))
    ;; dissociate the current-semantic-class, if needed
    (if-not (nil? current-semantic-class)
      (dissociate-semantic-class! annotation-id (:id current-semantic-class)))))

