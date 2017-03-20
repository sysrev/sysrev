(ns sysrev.misc
  (:require
   [sysrev.db.core :as db :refer [do-query do-execute to-sql-array]]
   [clojure-csv.core :refer [write-csv]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.shared.util :refer [map-values]]
   [clojure.string :as str]
   [sysrev.db.queries :as q]))

(defn articles-matching-regex-clause [field-name regexs]
  (sql/raw
   (str "( "
        (->>
         (mapv (fn [regex]
                 (format " (%s ~ '%s') "
                         (first (sql/format field-name))
                         regex))
               regexs)
         (str/join " OR "))
        " )")))

(defn delete-user-article-labels [user-id article-id]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (-> (delete-from :article-label)
      (where [:and
              [:= :article-id article-id]
              [:= :user-id user-id]])
      do-execute))

(defn delete-recent-user-labels [user-id interval-string]
  (->
   (delete-from [:article-label :al])
   (where [:and
           [:= :user-id user-id]
           [:>= :updated-time
            (sql/raw (format "now() - interval '%s'" interval-string))]])
   do-execute))

(defn fix-duplicate-label-values [label-id]
  (let [label (->> (q/select-label-by-id label-id [:*])
                   do-query first)]
    (let [{:keys [all-values inclusion-values]}
          (:definition label)]
      (let [label (cond-> label
                    all-values
                    (update-in [:definition :all-values]
                               #(->> % (remove empty?) distinct))
                    inclusion-values
                    (update-in [:definition :inclusion-values]
                               #(->> % (remove empty?) distinct)))]
        label))))

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
