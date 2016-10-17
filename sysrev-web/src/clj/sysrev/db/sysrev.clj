(ns sysrev.db.sysrev
  (:require
   [sysrev.db.core :refer [do-query do-execute do-transaction]]
   [sysrev.db.articles :as articles]
   [sysrev.db.users :as users]
   [sysrev.util :refer [map-values]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

;; TODO: use a project_id to support multiple systematic review projects

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
         (-> (select :article_id :criteria_id :user_id :answer)
             (from [:article_criteria :ac])
             (where [:and
                     [:!= :answer nil]
                     [:!= :confirm_time nil]])
             do-query)
         (group-by :user_id)
         (map-values
          (fn [uentries]
            (->> (group-by :article_id uentries)
                 (map-values
                  #(map-values (comp first (partial map :answer))
                               (group-by :criteria_id %)))))))
        user-articles (->> (keys entries)
                           (map
                            (fn [user-id]
                              (map (fn [article-id]
                                     [user-id article-id])
                                   (keys (get entries user-id)))))
                           (apply concat))
        criteria-ids (map :criteria_id (articles/all-criteria))
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
  (let [[articles labels label-values conflicts]
        (pvalues (sr-article-count)
                 (sr-label-counts)
                 (sr-label-value-counts)
                 (sr-conflict-counts))]
    {:articles articles
     :labels labels
     :label-values label-values
     :conflicts conflicts}))
