(ns sysrev.db.annotations
  (:require [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [returning]]
            [clj-time.coerce :as tc]
            [sysrev.db.core :refer
             [do-query do-execute to-jsonb sql-now with-project-cache with-transaction]]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [in? map-values] :as sutil]
            [sysrev.db.queries :as q]))

(defn create-semantic-class! [definition]
  (-> (insert-into :semantic-class)
      (values [{:definition (to-jsonb definition)}])
      (returning :id) do-query first :id))

(defn associate-annotation-semantic-class! [annotation-id semantic-class-id]
  (-> (insert-into :annotation-semantic-class)
      (values [{:annotation-id annotation-id
                :semantic-class-id semantic-class-id}])
      (returning :*) do-query first))

(defn create-annotation! [selection annotation context article-id]
  (-> (insert-into :annotation)
      (values [{:selection selection
                :annotation annotation
                :context (to-jsonb context)
                :article-id article-id}])
      (returning :id) do-query first :id))

(defn associate-annotation-article! [annotation-id article-id]
  (-> (insert-into :annotation-article)
      (values [{:annotation-id annotation-id
                :article-id article-id}])
      do-execute))

(defn annotation-semantic-class
  "Get the semantic-class associated with annotation-id"
  [annotation-id]
  (with-transaction
    (let [{:keys [semantic-class-id]} (-> (select :semantic-class-id)
                                          (from :annotation-semantic-class)
                                          (where [:= :annotation-id annotation-id])
                                          do-query first)]
      (-> (select :definition) (from :semantic-class)
          (where [:= :id semantic-class-id])
          do-query first :definition))))

(defn dissociate-semantic-class! [annotation-id semantic-class-id]
  (-> (delete-from :annotation-semantic-class)
      (where [:and
              [:= :annotation-id annotation-id]
              [:= :semantic-class-id semantic-class-id]])
      do-execute))

(defn associate-annotation-user! [annotation-id user-id]
  (-> (insert-into :annotation-web-user)
      (values [{:annotation-id annotation-id
                :user-id user-id}])
      do-execute))

(defn annotation-id->user-id
  "Given an annotation-id, return its owner's id"
  [annotation-id]
  (-> (select :user-id) (from :annotation-web-user)
      (where [:= :annotation-id annotation-id])
      do-query first :user-id))

;; extremely useful graph:
;; https://stackoverflow.com/questions/20602826/sql-server-need-join-but-where-not-equal-to
(defn user-defined-article-annotations [article-id]
  (with-transaction
    (let [annotations-articles (-> (select :*) (from :annotation-article)
                                   (where [:= :article-id article-id])
                                   do-query (->> (mapv :annotation-id)))]
      (if-not (empty? annotations-articles)
        (let [annotations (-> (select :*) (from [:annotation :a])
                              (left-join [:annotation-s3store :as3]
                                         [:= :a.id :as3.annotation-id])
                              (where [:and
                                      [:in :id annotations-articles]
                                      [:= :as3.annotation-id nil]])
                              do-query)]
          (map #(assoc % :semantic-class (annotation-semantic-class (:id %))
                       :user-id (annotation-id->user-id (:id %)))
               annotations))
        []))))

(defn associate-annotation-s3store! [annotation-id s3store-id]
  (-> (insert-into :annotation-s3store)
      (values [{:annotation-id annotation-id
                :s3store-id s3store-id}])
      do-execute))

(defn user-defined-article-pdf-annotations [article-id s3store-id]
  (with-transaction
    (let [annotations-articles (-> (select :*) (from :annotation-article)
                                   (where [:= :article-id article-id])
                                   do-query (->> (mapv :annotation-id)))]
      (if-not (empty? annotations-articles)
        (let [annotations (-> (select :*) (from [:annotation :a])
                              (join [:annotation-s3store :b]
                                    [:= :a.id :b.annotation-id])
                              (where [:and
                                      [:in :id annotations-articles]
                                      [:= :b.s3store-id s3store-id]])
                              do-query)]
          (map #(assoc %
                       :semantic-class (annotation-semantic-class (:id %))
                       :user-id (annotation-id->user-id (:id %)))
               annotations))
        []))))

(defn delete-annotation! [annotation-id]
  (q/delete-by-id :annotation :id annotation-id))

(defn annotation-id->project-id [annotation-id]
  (with-transaction
    (let [article-id (-> (select :article-id) (from :annotation-article)
                         (where [:= :annotation-id annotation-id])
                         do-query first :article-id)]
      (-> (select :project-id) (from :article)
          (where [:= :article-id article-id])
          do-query first :project-id))))

(defn update-annotation!
  "Update an annotation, with an optional semantic-class. If
  semantic-class is empty (blank string) or nil, a semantic-class of
  null will be associated with the annotation"
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
          current-semantic-class-id (-> (select :semantic-class-id) (from :annotation-semantic-class)
                                        (where [:= :annotation-id annotation-id])
                                        do-query first :semantic-class-id)
          current-semantic-class (-> (select :*) (from :semantic-class)
                                     (where [:= :id current-semantic-class-id])
                                     do-query first)
          new-semantic-class (-> (select :*) (from :semantic-class)
                                 (where [:= :definition (to-jsonb semantic-class)])
                                 do-query first)]
      ;; create a new semantic-class, if needed
      (if (nil? new-semantic-class)
        (let [new-id (create-semantic-class! semantic-class)]
          (associate-annotation-semantic-class! annotation-id new-id))
        (associate-annotation-semantic-class! annotation-id (:id new-semantic-class)))
      ;; dissociate the current-semantic-class, if needed
      (when (and current-semantic-class
                 (not= new-semantic-class current-semantic-class))
        (dissociate-semantic-class! annotation-id (:id current-semantic-class))))))

(defn project-annotations
  "Retrieve all annotations for project-id"
  [project-id]
  (-> (select :an.selection :an.annotation :a.public-id
              ;; some of these fields are needed to match 'text-context-article-field-match'
              :a.article-id :a.primary-title :a.secondary-title :a.abstract
              :sc.definition :s3.key :s3.filename
              :an.context :au.user-id)
      (from [:annotation :an])
      (join [:annotation-article :aa]  [:= :aa.annotation-id :an.id]
            [:article :a]              [:= :a.article-id :aa.article-id])
      (left-join [:annotation-web-user :au]         [:= :au.annotation-id :an.id]
                 [:annotation-semantic-class :asc]  [:= :asc.annotation-id :an.id]
                 [:semantic-class :sc]              [:= :sc.id :asc.semantic-class-id]
                 [:annotation-s3store :as3]         [:= :as3.annotation-id :an.id]
                 [:s3store :s3]                     [:= :s3.id :as3.s3store-id])
      (where [:= :a.project-id project-id])
      do-query))

(defn project-annotations-basic
  "Retrieve all annotations for project-id"
  [project-id]
  (-> (select :a.article-id :an.annotation :an.selection :an.context :sc.definition
              :au.user-id :s3.filename [:s3.key :file-key])
      (from [:annotation :an])
      (join [:annotation-article :aa]  [:= :aa.annotation-id :an.id]
            [:article :a]              [:= :a.article-id :aa.article-id])
      (left-join [:annotation-web-user :au]         [:= :au.annotation-id :an.id]
                 [:annotation-semantic-class :asc]  [:= :asc.annotation-id :an.id]
                 [:semantic-class :sc]              [:= :sc.id :asc.semantic-class-id]
                 [:annotation-s3store :as3]         [:= :as3.annotation-id :an.id]
                 [:s3store :s3]                     [:= :s3.id :as3.s3store-id])
      (where [:= :a.project-id project-id])
      do-query))

(defn project-annotation-status [project-id & {:keys [user-id]}]
  (-> (select :an.created :a.article-id :sc.definition)
      (from [:annotation :an])
      (join [:annotation-article :aa]          [:= :aa.annotation-id :an.id]
            [:article :a]                      [:= :a.article-id :aa.article-id]
            [:annotation-semantic-class :asc]  [:= :an.id :asc.annotation-id]
            [:semantic-class :sc]              [:= :sc.id :asc.semantic-class-id]
            [:annotation-web-user :au]         [:= :au.annotation-id :an.id])
      (where [:and
              [:= :a.project-id project-id]
              (if (nil? user-id) true
                  [:= :au.user-id user-id])])
      (->> do-query
           (group-by :definition)
           (map-values (fn [entries]
                         {:last-used (->> entries (map :created) (map tc/to-epoch)
                                          (apply max 0) tc/from-epoch tc/to-sql-time)
                          :count (count entries)})))))

(defn project-article-annotations [project-id & {:keys [include-disabled?]}]
  (with-project-cache project-id [:annotations :articles include-disabled?]
    (-> (select :an.created :aa.article-id :sc.definition :au.user-id)
        (from [:annotation :an])
        (join [:annotation-article :aa]   [:= :aa.annotation-id :an.id]
              [:article :a]               [:= :a.article-id :aa.article-id]
              [:annotation-web-user :au]  [:= :au.annotation-id :an.id])
        (left-join [:annotation-semantic-class :asc]  [:= :asc.annotation-id :an.id]
                   [:semantic-class :sc]              [:= :sc.id :asc.semantic-class-id])
        (where [:and [:= :a.project-id project-id]
                (if include-disabled? true [:= :a.enabled true])])
        (->> do-query
             (group-by :article-id)
             (map-values (fn [entries]
                           {:updated-time (->> entries (map :created) (map tc/to-epoch)
                                               (apply max 0) tc/from-epoch tc/to-sql-time)
                            :users (->> entries (map :user-id) distinct)
                            :count (count entries)
                            :classes (->> entries (map :definition) (remove nil?) distinct)}))))))

(defn text-context-article-field-match
  "Determine which field of an article text-context matches in article-id."
  [text-context article-id]
  (let [{:keys [primary-title secondary-title abstract]}
        (-> (select :primary-title :secondary-title :abstract)
            (from :article)
            (where [:= :article-id article-id])
            do-query first)]
    (condp = text-context
      primary-title :primary-title
      secondary-title :secondary-title
      abstract :abstract
      text-context)))
