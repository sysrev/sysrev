(ns sysrev.misc
  (:require [sysrev.db.core :refer [do-query do-execute]]
            [clojure-csv.core :refer [write-csv]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.util :refer [map-values]]
            [clojure.string :as str]))

(defn articles-matching-regex [project-id field-name regexs select-fields]
  (-> (apply select select-fields)
      (from :article)
      (where [:and
              [:= :project-id project-id]
              (sql/raw
               (str "( "
                    (->>
                     (mapv (fn [regex]
                             (format " (%s ~ '%s') "
                                     (first (sql/format field-name))
                                     regex))
                           regexs)
                     (str/join " OR "))
                    " )"))])
      do-query))

(defn delete-user-article-labels [user-id article-id]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (-> (delete-from :article-criteria)
      (where [:and
              [:= :article-id article-id]
              [:= :user-id user-id]])
      do-execute))

(defn delete-recent-user-labels [user-id interval-string]
  (->
   ;;(select :%count.*) (from [:article-criteria :ac])
   (delete-from [:article-criteria :ac])
   (where [:and
           [:= :user-id user-id]
           [:>= :updated-time
            (sql/raw (format "now() - interval '%s'" interval-string))]])
   do-execute))

;; finds articles where user has unconfirmed labels
(defn user-unconfirmed-articles [project-id user-id]
  (->>
   (-> (select :a.article-id)
       (from [:article-criteria :ac])
       (join [:article :a]
             [:= :a.article-id :ac.article-id])
       (merge-join [:project :p]
                   [:= :a.project-id :p.project-id])
       (where [:and
               [:= :p.project-id project-id]
               [:= :ac.user-id user-id]
               [:= :ac.confirm-time nil]])
       do-query)
   (mapv :article-id)))

;; finds labeled articles with no label by `user-id`
(defn articles-labeled-not-by-user [project-id user-id fields]
  (-> (apply select fields)
      (from [:article :a])
      (where
       [:and
        [:exists
         (-> (select :*)
             (from [:article-criteria :ac1])
             (where
              [:and
               [:!= :ac1.confirm-time nil]
               [:= :ac1.article-id :a.article-id]
               [:!= :ac1.user-id user-id]]))]
        [:not
         [:exists
          (-> (select :*)
              (from [:article-criteria :ac2])
              (where
               [:and
                [:!= :ac2.confirm-time nil]
                [:= :ac2.article-id :a.article-id]
                [:= :ac2.user-id user-id]]))]]])
      do-query))

(defn export-label-values-csv [project-id criteria-id path]
  (let [article-labels
        (->>
         (-> (select :ac.article-id :ac.answer)
             (from [:article-criteria :ac])
             (join [:article :a]
                   [:= :a.article-id :ac.article-id])
             (where [:and
                     [:= :a.project-id project-id]
                     [:= :ac.criteria-id criteria-id]
                     [:!= :ac.answer nil]
                     [:!= :ac.confirm-time nil]])
             do-query)
         (group-by :article-id)
         (map-values #(map :answer %))
         (map-values
          (fn [answers]
            (->>
             (distinct answers)
             (map
              (fn [answer]
                {:answer answer
                 :count (->> answers
                             (filter #(= % answer))
                             count)}))
             (sort-by :count >)
             first
             :answer))))]
    (->> article-labels
         vec
         (map #(map str %))
         write-csv
         (spit path))))

;; This finds examples of highly-similar and highly-dissimilar articles
;; relative to `article-id`.
;;
;; *** Not updated for multiple projects in db ***
#_
(defn find-similarity-examples-for-article [project-id sim-version-id article-id]
  (let [good
        (->>
         (-> (select :*)
             (from :article-similarity)
             (where [:and
                     [:= :sim-version-id sim-version-id]
                     [:or
                      [:= :lo-id article-id]
                      [:= :hi-id article-id]]
                     [:!= :lo-id :hi-id]
                     [:> :similarity 0.01]])
             (order-by [:similarity :asc])
             (limit 5)
             do-query)
         reverse
         (map (fn [e]
                (let [other-id (if (= (:lo-id e) article-id)
                                 (:hi-id e)
                                 (:lo-id e))
                      article (-> (select :article-id
                                          :primary-title
                                          :secondary-title
                                          :abstract)
                                  (from :article)
                                  (where [:= :article-id other-id])
                                  do-query first)]
                  (assoc article :distance (:similarity e)))))
         (filter #(>= (count (:abstract %)) 200))
         first)
        bad
        (->>
         (-> (select :*)
             (from :article-similarity)
             (where [:and
                     [:= :sim-version-id sim-version-id]
                     [:or
                      [:= :lo-id article-id]
                      [:= :hi-id article-id]]
                     [:!= :lo-id :hi-id]
                     [:> :similarity 0.9]
                     [:< :similarity 0.98]])
             (order-by [:similarity :desc])
             (limit 100)
             do-query)
         reverse
         (map (fn [e]
                (let [other-id (if (= (:lo-id e) article-id)
                                 (:hi-id e)
                                 (:lo-id e))
                      article (-> (select :article-id
                                          :primary-title
                                          :secondary-title
                                          :abstract)
                                  (from :article)
                                  (where [:= :article-id other-id])
                                  do-query first)]
                  (assoc article :distance (:similarity e)))))
         (filter #(>= (count (:abstract %)) 200))
         first)
        article (-> (select :article-id
                            :primary-title
                            :secondary-title
                            :abstract)
                    (from :article)
                    (where [:= :article-id article-id])
                    do-query first)]
    {:article article
     :similar good
     :unsimilar bad}))
