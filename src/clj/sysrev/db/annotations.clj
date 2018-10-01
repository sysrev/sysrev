(ns sysrev.db.annotations
  (:require [honeysql.helpers :as sqlh :refer [insert-into values where select from delete-from sset
                                               join left-join]]
            [honeysql-postgres.helpers :refer [returning]]
            [clj-time.coerce :as tc]
            [sysrev.db.core :refer
             [do-query do-execute to-jsonb sql-now with-project-cache with-transaction]]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [in? map-values] :as sutil]))

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

(defn create-annotation! [selection annotation context article-id]
  (-> (insert-into :annotation)
      (values [{:selection selection
                :annotation annotation
                :context (to-jsonb context)
                :article_id article-id}])
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
  (with-transaction
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
          :definition))))

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

(defn annotation-id->user-id
  "Given an annotation-id, return its owner's id"
  [annotation-id]
  (-> (select :user-id)
      (from :annotation_web_user)
      (where [:= :annotation_id
              annotation-id])
      do-query
      first
      :user-id))

;; extremely useful graph: https://stackoverflow.com/questions/20602826/sql-server-need-join-but-where-not-equal-to
(defn user-defined-article-annotations [article-id]
  (with-transaction
    (let [annotations-articles (-> (select :*)
                                   (from :annotation_article)
                                   (where [:= :article-id
                                           article-id])
                                   do-query
                                   (->> (mapv :annotation-id)))]
      (if-not (empty? annotations-articles)
        (let [annotations (-> (select :*)
                              (from [:annotation :a])
                              (left-join [:annotation_s3store :as3 ] [:= :a.id :as3.annotation_id])
                              (where [:and
                                      [:in :id annotations-articles]
                                      [:= :as3.annotation_id nil]])
                              do-query)]
          (map #(assoc %
                       :semantic-class
                       (annotation-semantic-class (:id %))
                       :user-id
                       (annotation-id->user-id (:id %)))
               annotations))
        []))))

(defn associate-annotation-s3store! [annotation-id s3store-id]
  (-> (insert-into :annotation_s3store)
      (values [{:annotation-id annotation-id
                :s3store-id s3store-id}])
      do-execute))

(defn user-defined-article-pdf-annotations [article-id s3store-id]
  (with-transaction
    (let [annotations-articles (-> (select :*)
                                   (from :annotation_article)
                                   (where [:= :article-id
                                           article-id])
                                   do-query
                                   (->> (mapv :annotation-id)))]
      (if-not (empty? annotations-articles)
        (let [annotations (-> (select :*)
                              (from [:annotation :a])
                              (join [:annotation_s3store :b] [:= :a.id :b.annotation_id])
                              (where [:and
                                      [:in :id annotations-articles]
                                      [:= :b.s3store-id s3store-id]])
                              do-query)]
          (map #(assoc %
                       :semantic-class (annotation-semantic-class (:id %))
                       :user-id (annotation-id->user-id (:id %)))
               annotations))
        []))))


(defn delete-annotation!
  [annotation-id]
  (-> (delete-from :annotation)
      (where [:= :id annotation-id])
      do-execute))

(defn annotation-id->project-id
  [annotation-id]
  (with-transaction
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
          :project-id))))

(defn project-active-semantic-classes
  "Get the semantic classes associated annotations that are active"
  [project-id]
  (with-transaction
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
      semantic-classes)))

(defn update-annotation!
  "Update an annotation, with an optional semantic-class. If semantic-class is empty (blank string) or nil, a semantic-class of null will be associated with the annotation"
  [annotation-id annotation & [semantic-class]]
  ;; update the annotation
  (with-transaction
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
      (when (and current-semantic-class
                 (not= new-semantic-class
                       current-semantic-class))
        (dissociate-semantic-class! annotation-id (:id current-semantic-class))))))

(defn project-annotations
  "Retrieve all annotations for project-id"
  [project-id]
  (-> (select :an.selection :an.annotation :a.public_id
              ;; some of these fields are needed to match 'text-context-article-field-match'
              :a.article_id :a.primary_title :a.secondary_title :a.abstract
              :sc.definition :s3.key :s3.filename
              :an.context :au.user-id)
      (from [:annotation :an])
      (join
       ;; annotation article
       [:annotation_article :aa]
       [:= :aa.annotation_id :an.id]
       ;; article
       [:article :a]
       [:= :a.article_id :aa.article_id]
       ;; annotation semantic class
       [:annotation_semantic_class :asc]
       [:= :an.id :asc.annotation_id]
       ;; semantic class
       [:semantic_class :sc]
       [:= :sc.id :asc.semantic_class_id])
      (left-join
       ;; annotation_s3store
       [:annotation_s3store :as3]
       [:= :an.id :as3.annotation_id]
       ;; s3store
       [:s3store :s3]
       [:= :s3.id :as3.s3store_id]
       ;; user
       [:annotation-web-user :au]
       [:= :au.annotation-id :an.id])
      (where [:= :a.project_id project-id])
      ;;(sql/format)
      do-query))

(defn project-annotation-status [project-id & {:keys [user-id]}]
  (-> (select :an.created :a.article_id :sc.definition
              #_ [:sc.created :sc-created])
      (from [:annotation :an])
      (join
       ;; annotation article
       [:annotation_article :aa]
       [:= :aa.annotation_id :an.id]
       ;; article
       [:article :a]
       [:= :a.article_id :aa.article_id]
       ;; annotation semantic class
       [:annotation_semantic_class :asc]
       [:= :an.id :asc.annotation_id]
       ;; semantic class
       [:semantic_class :sc]
       [:= :sc.id :asc.semantic_class_id]
       ;; user
       [:annotation-web-user :au]
       [:= :au.annotation-id :an.id])
      (where [:and
              [:= :a.project_id project-id]
              (if (nil? user-id)
                true
                [:= :au.user-id user-id])])
      (->> do-query
           (group-by :definition)
           (map-values
            (fn [entries]
              {:last-used (->> entries
                               (map :created)
                               (map tc/to-epoch)
                               (apply max 0)
                               tc/from-epoch
                               tc/to-sql-time)
               :count (count entries)})))))

(defn project-annotation-articles [project-id]
  (with-project-cache
    project-id [:annotations :articles]
    (-> (select :an.created :aa.article-id :sc.definition :au.user-id)
        (from [:annotation :an])
        (join
         ;; annotation article
         [:annotation_article :aa]
         [:= :aa.annotation_id :an.id]
         ;; article
         [:article :a]
         [:= :a.article_id :aa.article_id]
         ;; user
         [:annotation-web-user :au]
         [:= :au.annotation-id :an.id])
        (left-join
         ;; annotation semantic class
         [:annotation_semantic_class :asc]
         [:= :an.id :asc.annotation_id]
         ;; semantic class
         [:semantic_class :sc]
         [:= :sc.id :asc.semantic_class_id])
        (where [:= :a.project_id project-id])
        (->> do-query
             (group-by :article-id)
             (map-values
              (fn [entries]
                {:updated-time (->> entries
                                    (map :created)
                                    (map tc/to-epoch)
                                    (apply max 0)
                                    tc/from-epoch
                                    tc/to-sql-time)
                 :users (->> entries (map :user-id) distinct)
                 :count (count entries)
                 :classes (->> entries
                               (map :definition)
                               (remove nil?)
                               distinct)}))))))

(defn text-context-article-field-match
  "Determine which field of an article text-context matches in article-id."
  [text-context article-id]
  (let [article (-> (select :primary_title :secondary_title :abstract)
                    (from :article)
                    (where [:= :article_id article-id])
                    do-query
                    first)
        {:keys [primary-title secondary-title abstract]} article]
    (condp = text-context
      primary-title :primary-title
      secondary-title :secondary-title
      abstract :abstract
      text-context)))
