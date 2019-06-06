(ns sysrev.db.annotations
  (:require [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer [returning]]
            [clj-time.coerce :as tc]
            [sysrev.db.core :as db :refer [do-query do-execute with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [in? map-values] :as sutil]))

(defn create-semantic-class! [definition]
  (-> (insert-into :semantic-class)
      (values [{:definition (db/to-jsonb definition)}])
      (returning :semantic-class-id) do-query first :semantic-class-id))

(defn associate-annotation-semantic-class! [annotation-id semantic-class-id]
  (-> (insert-into :ann-semantic-class)
      (values [{:annotation-id annotation-id
                :semantic-class-id semantic-class-id}])
      (returning :*) do-query first))

(defn create-annotation! [selection annotation context article-id]
  (-> (insert-into :annotation)
      (values [{:selection selection
                :annotation annotation
                :context (db/to-jsonb context)
                :article-id article-id}])
      (returning :annotation-id) do-query first :annotation-id))

(defn associate-annotation-article! [annotation-id article-id]
  (-> (insert-into :ann-article)
      (values [{:annotation-id annotation-id
                :article-id article-id}])
      do-execute))

(defn ann-semantic-class
  "Get the semantic-class associated with annotation-id"
  [annotation-id]
  (with-transaction
    (let [{:keys [semantic-class-id]} (-> (select :semantic-class-id)
                                          (from :ann-semantic-class)
                                          (where [:= :annotation-id annotation-id])
                                          do-query first)]
      (-> (select :definition) (from :semantic-class)
          (where [:= :semantic-class-id semantic-class-id])
          do-query first :definition))))

(defn dissociate-semantic-class! [annotation-id semantic-class-id]
  (-> (delete-from :ann-semantic-class)
      (where [:and
              [:= :annotation-id annotation-id]
              [:= :semantic-class-id semantic-class-id]])
      do-execute))

(defn associate-annotation-user! [annotation-id user-id]
  (-> (insert-into :ann-user)
      (values [{:annotation-id annotation-id
                :user-id user-id}])
      do-execute))

(defn annotation-id->user-id
  "Given an annotation-id, return its owner's id"
  [annotation-id]
  (-> (select :user-id) (from :ann-user)
      (where [:= :annotation-id annotation-id])
      do-query first :user-id))

;; extremely useful graph:
;; https://stackoverflow.com/questions/20602826/sql-server-need-join-but-where-not-equal-to
(defn user-defined-article-annotations [article-id]
  (with-transaction
    (->> (when-let [article-ann-ids (seq (-> (select :annotation-id) (from :ann-article)
                                             (where [:= :article-id article-id])
                                             do-query (->> (map :annotation-id))))]
           (-> (select :*) (from [:annotation :a])
               (left-join [:ann-s3store :as3] [:= :a.annotation-id :as3.annotation-id])
               (where [:and
                       [:in :a.annotation-id article-ann-ids]
                       [:= :as3.annotation-id nil]])
               do-query))
         (mapv #(-> % (assoc :semantic-class (ann-semantic-class (:annotation-id %))
                             :user-id (annotation-id->user-id (:annotation-id %))))))))

(defn associate-annotation-s3! [annotation-id s3-id]
  (-> (insert-into :ann-s3store)
      (values [{:annotation-id annotation-id
                :s3-id s3-id}])
      do-execute))

(defn user-defined-article-pdf-annotations [article-id s3-id]
  (with-transaction
    (->> (when-let [article-ann-ids (seq (-> (select :*) (from :ann-article)
                                             (where [:= :article-id article-id])
                                             do-query (->> (map :annotation-id))))]
           (-> (select :*) (from [:annotation :a])
               (join [:ann-s3store :as3] [:= :a.annotation-id :as3.annotation-id])
               (where [:and
                       [:in :a.annotation-id article-ann-ids]
                       [:= :as3.s3-id s3-id]])
               do-query))
         (mapv #(-> % (assoc :semantic-class (ann-semantic-class (:annotation-id %))
                             :user-id (annotation-id->user-id (:annotation-id %))))))))

(defn annotation-id->project-id [annotation-id]
  (with-transaction
    (let [article-id (-> (select :article-id) (from :ann-article)
                         (where [:= :annotation-id annotation-id])
                         do-query first :article-id)]
      (-> (select :project-id) (from :article)
          (where [:= :article-id article-id])
          do-query first :project-id))))

(defn delete-annotation! [annotation-id]
  (with-transaction
    (let [project-id (annotation-id->project-id annotation-id)]
      (try (q/delete-by-id :annotation :annotation-id annotation-id)
           (finally (some-> project-id (db/clear-project-cache)))))))

(defn update-annotation!
  "Update an annotation, with an optional semantic-class. If
  semantic-class is empty (blank string) or nil, a semantic-class of
  null will be associated with the annotation"
  [annotation-id annotation & [semantic-class]]
  ;; update the annotation
  (with-transaction
    (try (-> (sqlh/update :annotation)
             (sset {:annotation annotation})
             (where [:= :annotation-id annotation-id])
             do-execute)
         ;; update the semantic-class
         (let [semantic-class (not-empty semantic-class)
               current-class-id (-> (select :semantic-class-id) (from :ann-semantic-class)
                                    (where [:= :annotation-id annotation-id])
                                    do-query first :semantic-class-id)
               current-class (-> (select :*) (from :semantic-class)
                                 (where [:= :semantic-class-id current-class-id])
                                 do-query first)
               new-class (-> (select :*) (from :semantic-class)
                             (where [:= :definition (db/to-jsonb semantic-class)])
                             do-query first)]
           ;; create a new semantic-class, if needed
           (if (nil? new-class)
             (let [new-id (create-semantic-class! semantic-class)]
               (associate-annotation-semantic-class! annotation-id new-id))
             (associate-annotation-semantic-class! annotation-id (:semantic-class-id new-class)))
           ;; dissociate the current-semantic-class, if needed
           (when (and current-class (not= new-class current-class))
             (dissociate-semantic-class! annotation-id (:semantic-class-id current-class))))
         (finally (some-> (annotation-id->project-id annotation-id)
                          (db/clear-project-cache))))))

(defn project-annotations
  "Retrieve all annotations for project-id"
  [project-id]
  (-> (select :an.selection :an.annotation :a.public-id
              ;; some of these fields are needed to match 'text-context-article-field-match'
              :a.article-id :a.primary-title :a.secondary-title :a.abstract
              :sc.definition :s3.key :s3.filename
              :an.context :au.user-id)
      (from [:annotation :an])
      (join [:ann-article :aa]  [:= :aa.annotation-id :an.annotation-id]
            [:article :a]       [:= :a.article-id :aa.article-id])
      (left-join [:ann-user :au]             [:= :au.annotation-id :an.annotation-id]
                 [:ann-semantic-class :asc]  [:= :asc.annotation-id :an.annotation-id]
                 [:semantic-class :sc]       [:= :sc.semantic-class-id :asc.semantic-class-id]
                 [:ann-s3store :as3]         [:= :as3.annotation-id :an.annotation-id]
                 [:s3store :s3]              [:= :s3.s3-id :as3.s3-id])
      (where [:= :a.project-id project-id])
      do-query))

(defn project-annotations-basic
  "Retrieve all annotations for project-id"
  [project-id]
  (-> (select :a.article-id :an.annotation :an.selection :an.context :sc.definition
              :au.user-id :s3.filename [:s3.key :file-key])
      (from [:annotation :an])
      (join [:ann-article :aa]  [:= :aa.annotation-id :an.annotation-id]
            [:article :a]              [:= :a.article-id :aa.article-id])
      (left-join [:ann-user :au]             [:= :au.annotation-id :an.annotation-id]
                 [:ann-semantic-class :asc]  [:= :asc.annotation-id :an.annotation-id]
                 [:semantic-class :sc]       [:= :sc.semantic-class-id :asc.semantic-class-id]
                 [:ann-s3store :as3]         [:= :as3.annotation-id :an.annotation-id]
                 [:s3store :s3]              [:= :s3.s3-id :as3.s3-id])
      (where [:= :a.project-id project-id])
      do-query))

(defn project-annotation-status [project-id & {:keys [user-id]}]
  (-> (select :an.created :a.article-id :sc.definition)
      (from [:annotation :an])
      (join [:ann-article :aa]          [:= :aa.annotation-id :an.annotation-id]
            [:article :a]               [:= :a.article-id :aa.article-id]
            [:ann-semantic-class :asc]  [:= :an.annotation-id :asc.annotation-id]
            [:semantic-class :sc]       [:= :sc.semantic-class-id :asc.semantic-class-id]
            [:ann-user :au]             [:= :au.annotation-id :an.annotation-id])
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
  (db/with-project-cache project-id [:annotations :articles include-disabled?]
    (-> (select :an.created :aa.article-id :sc.definition :au.user-id)
        (from [:annotation :an])
        (join [:ann-article :aa]  [:= :aa.annotation-id :an.annotation-id]
              [:article :a]       [:= :a.article-id :aa.article-id]
              [:ann-user :au]     [:= :au.annotation-id :an.annotation-id])
        (left-join [:ann-semantic-class :asc]  [:= :asc.annotation-id :an.annotation-id]
                   [:semantic-class :sc]       [:= :sc.semantic-class-id :asc.semantic-class-id])
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
