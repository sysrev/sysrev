(ns sysrev.misc
  (:require [clojure.string :as str]
            [clojure-csv.core :refer [write-csv]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction to-sql-array to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.pubmed :as pubmed]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? map-values]]))

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
            (let [pxml (-> raw util/parse-xml-str :content first)
                  abstract
                  (-> (util/xml-find
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

(defn merge-article-labels [article-ids]
  (let [labels
        (->> article-ids
             (mapv (fn [article-id]
                     (-> (q/select-article-by-id article-id [:al.*])
                         (q/join-article-labels)
                         do-query)))
             (apply concat)
             (group-by :user-id)
             (map-values #(group-by :article-id %))
             (map-values vec)
             (map-values #(sort-by (comp count second) > %)))]
    (doseq [[user-id ulabels] labels]
      (let [confirmed-labels
            (->> ulabels
                 (filter
                  (fn [[article-id alabels]]
                    (some #(not= (:confirm-time %) nil)
                          alabels))))
            keep-labels
            (cond (not (empty? confirmed-labels))
                  (second (first confirmed-labels))
                  :else
                  (second (first ulabels)))]
        (when (not (empty? keep-labels))
          (println
           (format "keeping %d labels for user=%s"
                   (count keep-labels) user-id))
          (with-transaction
            (doseq [article-id article-ids]
              (-> (delete-from :article-label)
                  (where [:and
                          [:= :article-id article-id]
                          [:= :user-id user-id]])
                  do-execute)
              (-> (insert-into :article-label)
                  (values
                   (->> keep-labels
                        (map
                         #(-> %
                              (assoc :article-id article-id)
                              (update :answer to-jsonb)
                              (dissoc :article-label-id)
                              (dissoc :article-label-local-id)))))
                  do-execute))))))
    true))
