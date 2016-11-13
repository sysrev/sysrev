(ns sysrev.db.articles
  (:require
   [clojure.java.jdbc :as j]
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction
     *active-project* active-db to-sql-array]]
   [sysrev.predict.core :refer [latest-predict-run]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defn alter-criteria [id values]
  (-> (sqlh/update :criteria)
      (sset values)
      (where [:= :criteria-id id])
      do-execute))

(defn fix-duplicate-authors-entries [project-id]
  (->>
   (-> (select :article-id :authors)
       (from :article)
       (where [:= :project-id project-id])
       do-query)
   (pmap
    (fn [a]
      (let [authors (:authors a)
            distinct-authors (vec (distinct authors))]
        (when (< (count distinct-authors) (count authors))
          (println (format "fixing authors field for article #%d"
                           (:article-id a)))
          (println (pr-str authors))
          (println (pr-str distinct-authors))
          (let [sql-authors
                (to-sql-array "text" distinct-authors)]
            (-> (sqlh/update :article)
                (sset {:authors sql-authors})
                (where [:= :article-id (:article-id a)])
                do-execute))))))
   doall)
  true)

(defn get-article [article-id]
  (-> (select :*)
      (from :article)
      (where [:= :article-id article-id])
      do-query
      first))

(defn to-article [article-or-id]
  (cond (integer? article-or-id)
        (get-article article-or-id)
        (and (map? article-or-id) (:article-id article-or-id))
        article-or-id
        :else nil))

(defn article-predict-score [article & [predict-run-id]]
  (let [article (to-article article)
        article-id (:article-id article)
        project-id (:project-id article)]
    (when-let [predict-run-id
               (or predict-run-id
                   (:predict-run-id (latest-predict-run project-id)))]
      (-> (select [:lp.val :score])
          (from [:label-predicts :lp])
          (join [:criteria :c]
                [:= :c.criteria-id :lp.criteria-id])
          (where [:and
                  [:= :lp.article-id article-id]
                  [:= :lp.predict-run-id predict-run-id]
                  [:= :lp.stage 1]
                  [:= :c.name "overall include"]])
          do-query first :score))))

(defn add-article [article project-id]
  (-> (insert-into :article)
      (values [(-> (merge article {:project-id project-id})
                   (update :authors #(to-sql-array "text" %))
                   (update :keywords #(to-sql-array "text" %)))])
      (returning :article-id)
      do-query))
