(ns sysrev.db.sysrev
  (:require
   [sysrev.db.core :refer [do-query do-execute do-transaction]]
   [sysrev.db.articles :as articles]
   [sysrev.db.users :as users]
   [sysrev.util :refer [map-values mapify-by-id mapify-group-by-id]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

;; TODO: use a project_id to support multiple systematic review projects

(defn sr-article-count []
  (-> (select :%count.*)
      (from :article)
      (where true)
      do-query first :count))

(defn sr-overall-labels []
  (->>
   (-> (select :a.article_id :ac.user_id :ac.answer)
       (from [:article :a])
       (join [:article_criteria :ac]
             [:= :ac.article_id :a.article_id])
       (merge-join [:criteria :c]
                   [:= :ac.criteria_id :c.criteria_id])
       (where [:and
               [:= :c.name "overall include"]
               [:or
                [:= :ac.answer true]
                [:= :ac.answer false]]])
       do-query)
   (mapify-group-by-id :article_id true)))

(defn sr-label-conflicts []
  (->> (sr-overall-labels)
       (filter (fn [[aid labels]]
                 (< 1 (->> labels (map :answer) distinct count))))
       (apply concat)
       (apply hash-map)))

(defn sr-conflict-counts []
  (let [conflicts (sr-label-conflicts)
        resolved? (fn [[aid labels :as conflict]]
                    (> (count labels) 2))
        n-total (count conflicts)
        n-pending (->> conflicts (remove resolved?) count)
        n-resolved (- n-total n-pending)]
    {:total n-total
     :pending n-pending
     :resolved n-resolved}))

(defn sr-label-counts []
  (let [labels (sr-overall-labels)
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
             (where [:or
                     [:= :answer true]
                     [:= :answer false]])
             do-query)
         (mapify-group-by-id :user_id true)
         (map-values
          (fn [uentries]
            (->> (mapify-group-by-id :article_id true uentries)
                 (map-values
                  #(map-values (comp first (partial map :answer))
                               (mapify-group-by-id :criteria_id true %)))))))
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
  {:articles (sr-article-count)
   :labels (sr-label-counts)
   :label-values (sr-label-value-counts)
   :conflicts (sr-conflict-counts)})
