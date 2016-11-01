(ns sysrev.db.sysrev
  (:require
   [sysrev.db.core :refer [do-query do-execute do-transaction]]
   [sysrev.db.articles :as articles]
   [sysrev.db.users :as users]
   [sysrev.predict.core :refer [latest-predict-run]]
   [sysrev.predict.report :refer [predict-summary]]
   [sysrev.util :refer [map-values]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [clojure-csv.core :refer [write-csv]]))

;; TODO: use a project-id to support multiple systematic review projects

(defn sr-article-count []
  (-> (select :%count.*)
      (from :article)
      (where true)
      do-query first :count))

(defn sr-conflict-counts []
  (let [conflicts (articles/all-label-conflicts)
        resolved? (fn [[aid labels :as conflict]]
                    (> (count labels) 2))
        n-total (count conflicts)
        n-pending (->> conflicts (remove resolved?) count)
        n-resolved (- n-total n-pending)]
    {:total n-total
     :pending n-pending
     :resolved n-resolved}))

(defn sr-label-counts []
  (let [labels (articles/all-overall-labels)
        counts (->> labels vals (map count))]
    {:any (count labels)
     :single (->> counts (filter #(= % 1)) count)
     :double (->> counts (filter #(= % 2)) count)
     :multi (->> counts (filter #(> % 2)) count)}))

(defn sr-label-value-counts
  "Returns counts of [true, false, unknown] label values for each criteria.
  true/false can be counted by the labels saved by all users.
  'unknown' values are counted when the user has set a value for at least one
  label on the article."
  []
  (let [entries
        (->>
         (-> (select :article-id :criteria-id :user-id :answer)
             (from [:article-criteria :ac])
             (where [:and
                     [:!= :answer nil]
                     [:!= :confirm-time nil]])
             do-query)
         (group-by :user-id)
         (map-values
          (fn [uentries]
            (->> (group-by :article-id uentries)
                 (map-values
                  #(map-values (comp first (partial map :answer))
                               (group-by :criteria-id %)))))))
        user-articles (->> (keys entries)
                           (map
                            (fn [user-id]
                              (map (fn [article-id]
                                     [user-id article-id])
                                   (keys (get entries user-id)))))
                           (apply concat))
        criteria-ids (map :criteria-id (articles/all-criteria))
        ua-label-value
        (fn [user-id article-id criteria-id]
          (get-in entries [user-id article-id criteria-id]))
        answer-count
        (fn [criteria-id answer]
          (->> user-articles
               (filter
                (fn [[user-id article-id]]
                  (= answer (ua-label-value
                             user-id article-id criteria-id))))
               count))]
    (->> criteria-ids
         (map
          (fn [criteria-id]
            [criteria-id
             {:true (answer-count criteria-id true)
              :false (answer-count criteria-id false)
              :unknown (answer-count criteria-id nil)}]))
         (apply concat)
         (apply hash-map))))

(defn sr-summary []
  (let [project-id 1
        [predict articles labels label-values conflicts]
        (pvalues (predict-summary
                  (:predict-run-id (latest-predict-run project-id)))
                 (sr-article-count)
                 (sr-label-counts)
                 (sr-label-value-counts)
                 (sr-conflict-counts))]
    {:articles articles
     :labels labels
     :label-values label-values
     :conflicts conflicts
     :predict predict}))

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
