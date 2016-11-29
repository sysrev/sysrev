(ns sysrev.db.articles
  (:require
   [clojure.java.jdbc :as j]
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer [do-query do-execute to-sql-array]]
   [sysrev.predict.core :refer [latest-predict-run]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

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
  (let [article (-> (select :*)
                    (from :article)
                    (where [:= :article-id article-id])
                    do-query
                    first)
        locations (->> (-> (select :source :external_id)
                           (from [:article-location :al])
                           (where [:= :al.article-id article-id])
                           do-query)
                       (group-by :source))]
    (assoc article :locations locations)))

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

(defn article-to-sql
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article & [conn]]
  (-> article
      (update :authors #(to-sql-array "text" % conn))
      (update :keywords #(to-sql-array "text" % conn))))

(defn add-article [article project-id]
  (-> (insert-into :article)
      (values [(merge (article-to-sql article)
                      {:project-id project-id})])
      (returning :article-id)
      do-query))

(defn article-locations [article-id]
  {article-id
   (->>
    (-> (select :*)
        (from :article-location)
        (where [:= :article-id article-id])
        do-query)
    (map #(dissoc % :article-id)))})

(defn articles-without-location [project-id]
  (-> (select :*)
      (from [:article :a])
      (where
       [:and
        [:= :a.project-id project-id]
        [:not
         [:exists
          (-> (select :*)
              (from [:article-location :al])
              (where
               [:and
                [:= :al.article-id :a.article-id]
                [:!= :al.source "pubmed"]]))]]])
      do-query))
