;;
;; This code is mostly specific to the Immunotherapy project.
;;

(ns sysrev.custom.immuno
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [sysrev.source.endnote :refer [parse-endnote-file]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.users :as users]
            [sysrev.db.labels :as labels]
            [sysrev.util :refer [xml-find]]
            [clojure.string :as str]
            [sysrev.db.queries :as q]
            [sysrev.misc :refer [articles-matching-regex-clause]]
            [clojure.java.jdbc :as j]))

(defn match-article-id
  "Attempt to find an article-id in the database which is the best match
  for the given fields. Requires an exact match on title and journal fields,
  allows for differences in remote-database-name and attempts to select an 
  appropriate match."
  [project-id title journal rdb-name]
  (let [results (-> (select :article-id :remote-database-name)
                    (from :article)
                    (where [:and
                            [:= :project-id project-id]
                            [:= :primary-title title]
                            [:= :secondary-title journal]])
                    (order-by :article-id)
                    do-query)]
    (if (empty? results)
      (do (println (format "no article match: '%s' '%s' '%s'"
                           title journal rdb-name))
          nil)
      ;; Attempt a few different ways of finding the best match based
      ;; on remote-database-name.
      (or
       ;; exact match
       (->> results
            (filter #(= (:remote-database-name %) rdb-name))
            first
            :article-id)
       ;; case-insensitive match
       (->> results
            (filter #(and (string? (:remote-database-name %))
                          (string? rdb-name)
                          (= (-> % :remote-database-name str/lower-case)
                             (-> rdb-name str/lower-case))))
            first
            :article-id)
       ;; prefer embase
       (->> results
            (filter #(and (string? (:remote-database-name %))
                          (= (-> % :remote-database-name str/lower-case)
                             "embase")))
            first
            :article-id)
       ;; then prefer medline
       (->> results
            (filter #(and (string? (:remote-database-name %))
                          (= (-> % :remote-database-name str/lower-case)
                             "medline")))
            first
            :article-id)
       ;; otherwise use earliest inserted article match
       (-> results first :article-id)))))

;; (Deleted) This queried project for duplicate pairs using
;; obsolete article_similarity table
(defn find-duplicate-article-pairs [project-id]
  nil)

(defn merge-duplicate-articles [article-ids]
  (when-not (empty? article-ids)
    (let [articles
          (->> article-ids
               (pmap #(q/query-article-by-id % [:*]))
               doall)
          single-id
          (->> articles
               (sort-by #(vector (-> % :urls count)
                                 (-> % :locations count)
                                 (-> % :abstract count)))
               reverse
               first
               :article-id)]
      (when (every? :enabled articles)
        (assert single-id)
        (labels/merge-article-labels article-ids)
        (doseq [article-id article-ids]
          (q/set-article-enabled-where
           (= article-id single-id)
           [:= :article-id article-id]))
        (println
         (format "using article '%s' from '%s'" single-id (pr-str article-ids)))))))

(defn merge-all-duplicates [project-id]
  (let [pairs (find-duplicate-article-pairs project-id)]
    (println (format "found %d pairs" (count pairs)))
    (doseq [entry pairs]
      (merge-duplicate-articles [(:article-id-1 entry)
                                 (:article-id-2 entry)]))))
