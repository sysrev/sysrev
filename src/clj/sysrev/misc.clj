(ns sysrev.misc
  (:require [clojure.string :as str]
            [clojure-csv.core :refer [write-csv]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db :refer [do-query do-execute to-sql-array]]
            [sysrev.db.queries :as q]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.util :as u]
            [sysrev.shared.util :as su :refer [in? map-values]]))

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

(defn reload-project-abstracts [project-id]
  (let [articles (-> (select :article-id :raw)
                     (from [:article :a])
                     (where
                      [:and
                       [:!= :raw nil]
                       [:= :project-id project-id]])
                     do-query)]
    (->> articles
         (pmap
          (fn [{:keys [article-id raw]}]
            (let [pxml (-> raw u/parse-xml-str :content first)
                  abstract
                  (-> (u/xml-find
                       pxml [:MedlineCitation :Article :Abstract :AbstractText])
                      pubmed/parse-abstract)]
              (when-not (empty? abstract)
                (-> (sqlh/update :article)
                    (sset {:abstract abstract})
                    (where [:= :article-id article-id])
                    do-execute))
              (println (str "processed #" article-id)))))
         doall)
    (println (str "updated " (count articles) " articles"))))
