(ns sysrev.misc
  (:require [sysrev.db.core :refer [do-query do-execute]]
            [clojure-csv.core :refer [write-csv]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.util :refer [map-values]]
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

(defn alter-label [id values]
  (-> (sqlh/update :label)
      (sset values)
      (where [:= :label-id id])
      do-execute))
