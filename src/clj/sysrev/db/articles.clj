(ns sysrev.db.articles
  (:require
   [clojure.java.jdbc :as j]
   [sysrev.shared.util :refer [map-values]]
   [sysrev.db.core :as db :refer [do-query do-execute to-sql-array]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.queries :as q]))

(defn fix-duplicate-authors-entries [project-id]
  (->>
   (q/select-project-articles
    project-id [:article-id :authors]
    {:include-disabled? true})
   (pmap
    (fn [a]
      (let [authors (:authors a)
            distinct-authors (vec (distinct authors))]
        (when (< (count distinct-authors) (count authors))
          (println
           (format "fixing authors field for article #%d" (:article-id a)))
          (println (pr-str authors))
          (println (pr-str distinct-authors))
          (let [sql-authors
                (to-sql-array "text" distinct-authors)]
            (-> (sqlh/update :article)
                (sset {:authors sql-authors})
                (where [:= :article-id (:article-id a)])
                do-execute))))))
   doall)
  (db/clear-project-cache project-id)
  true)

(defn article-to-sql
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article & [conn]]
  (-> article
      (update :authors #(to-sql-array "text" % conn))
      (update :keywords #(to-sql-array "text" % conn))
      (update :urls #(to-sql-array "text" % conn))
      (update :document-ids #(to-sql-array "text" % conn))))

(defn add-article [article project-id]
  (-> (insert-into :article)
      (values [(merge (article-to-sql article)
                      {:project-id project-id})])
      (returning :article-id)
      do-query))
