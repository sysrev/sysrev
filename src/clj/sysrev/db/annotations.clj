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
    (let [ ;; TODO: add migration to remove duplicate ann-semantic-class entries
          {:keys [semantic-class-id]} (q/find-one :ann-semantic-class {:annotation-id annotation-id})]
      (q/find-one :semantic-class {:semantic-class-id semantic-class-id} :definition))))

(defn dissociate-semantic-class! [annotation-id semantic-class-id]
  (q/delete :ann-semantic-class {:annotation-id annotation-id
                                 :semantic-class-id semantic-class-id}))

(defn associate-annotation-user! [annotation-id user-id]
  (-> (insert-into :ann-user)
      (values [{:annotation-id annotation-id
                :user-id user-id}])
      do-execute))

(defn annotation-id->user-id
  "Given an annotation-id, return its owner's id"
  [annotation-id]
  (q/find-one :ann-user {:annotation-id annotation-id} :user-id))

;; extremely useful graph:
;; https://stackoverflow.com/questions/20602826/sql-server-need-join-but-where-not-equal-to
(defn user-defined-article-annotations [article-id]
  (with-transaction
    (->> (when-let [article-ann-ids (q/find :ann-article {:article-id article-id}
                                            :annotation-id)]
           (q/find [:annotation :a] {:a.annotation-id article-ann-ids
                                     :as3.annotation-id nil}
                   :*, :left-join [[:a :ann-s3store :as3 :annotation-id]]))
         (mapv #(merge % {:semantic-class (ann-semantic-class (:annotation-id %))
                          :user-id (annotation-id->user-id (:annotation-id %))})))))

(defn associate-annotation-s3! [annotation-id s3-id]
  (-> (insert-into :ann-s3store)
      (values [{:annotation-id annotation-id
                :s3-id s3-id}])
      do-execute))

(defn user-defined-article-pdf-annotations [article-id s3-id]
  (with-transaction
    (->> (when-let [article-ann-ids (q/find :ann-article {:article-id article-id} :annotation-id)]
           (q/find [:annotation :a] {:a.annotation-id article-ann-ids
                                     :as3.s3-id s3-id}
                   :*, :join [[:a :ann-s3store :as3 :annotation-id]]))
         (mapv #(merge % {:semantic-class (ann-semantic-class (:annotation-id %))
                          :user-id (annotation-id->user-id (:annotation-id %))})))))

(defn annotation-id->project-id [annotation-id]
  (with-transaction
    (when-let [article-id (q/find-one :ann-article {:annotation-id annotation-id} :article-id)]
      (q/find-one :article {:article-id article-id} :project-id))))

(defn delete-annotation! [annotation-id]
  (with-transaction
    (let [project-id (annotation-id->project-id annotation-id)]
      (try (q/delete :annotation {:annotation-id annotation-id})
           (finally (some-> project-id (db/clear-project-cache)))))))

(defn update-annotation!
  "Update an annotation, with an optional semantic-class. If
  semantic-class is empty (blank string) or nil, a semantic-class of
  null will be associated with the annotation"
  [annotation-id annotation & [semantic-class]]
  ;; update the annotation
  (with-transaction
    (try (q/modify :annotation {:annotation-id annotation-id}
                   {:annotation annotation})
         ;; update the semantic-class
         (let [semantic-class (not-empty semantic-class)
               current-class-id (q/find-one :ann-semantic-class {:annotation-id annotation-id}
                                            :semantic-class-id)
               current-class (q/find-one :semantic-class {:semantic-class-id current-class-id})
               ;; NOTE: these semantic-class entries are global across database
               ;;       (this means they must never be deleted or modified)
               new-class (first (q/find :semantic-class {:definition (db/to-jsonb semantic-class)}))]
           ;; dissociate current class
           (when current-class
             (dissociate-semantic-class! annotation-id (:semantic-class-id current-class)))
           ;; associate new class
           (let [ ;; take existing class entry, or create if not found
                 new-class-id (or (:semantic-class-id new-class)
                                  (create-semantic-class! semantic-class))]
             (associate-annotation-semantic-class! annotation-id new-class-id)))
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
  (let [article (q/find-one :article {:article-id article-id}
                            [:primary-title :secondary-title :abstract])]
    (or (->> (keys article)
             (filter #(= text-context (get article %)))
             first)
        text-context)))
