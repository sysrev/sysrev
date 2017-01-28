(ns sysrev.db.articles
  (:require
   [clojure.java.jdbc :as j]
   [sysrev.shared.util :refer [map-values]]
   [sysrev.db.core :as db :refer
    [do-query do-execute to-sql-array sql-now with-project-cache
     clear-project-cache]]
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

(defn set-user-article-note [article-id user-id note-name content]
  (let [note-name (name note-name)
        pnote (-> (q/select-article-by-id article-id [:pn.*])
                  (merge-join [:project :p]
                              [:= :p.project-id :a.project-id])
                  (q/with-project-note note-name)
                  do-query first)
        anote (-> (q/select-article-by-id article-id [:an.*])
                  (q/with-article-note note-name user-id)
                  do-query first)]
    (assert pnote "note type not defined in project")
    (assert (:project-id pnote) "project-id not found")
    (clear-project-cache (:project-id pnote) [:article article-id :notes])
    (clear-project-cache (:project-id pnote) [:users user-id :notes])
    (let [fields {:article-id article-id
                  :user-id user-id
                  :project-note-id (:project-note-id pnote)
                  :content content
                  :updated-time (sql-now)}]
      (if (nil? anote)
        (-> (sqlh/insert-into :article-note)
            (values [fields])
            (returning :*)
            do-query)
        (-> (sqlh/update :article-note)
            (where [:and
                    [:= :article-id article-id]
                    [:= :user-id user-id]
                    [:= :project-note-id (:project-note-id pnote)]])
            (sset fields)
            (returning :*)
            do-query)))))

(defn article-user-notes-map [project-id article-id]
  (with-project-cache
    project-id [:article article-id :notes :user-notes-map]
    (->>
     (-> (q/select-article-by-id article-id [:an.* :pn.name])
         (q/with-article-note)
         do-query)
     (group-by :user-id)
     (map-values
      #(->> %
            (group-by :name)
            (map-values first)
            (map-values :content))))))
