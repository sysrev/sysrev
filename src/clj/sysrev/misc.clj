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
