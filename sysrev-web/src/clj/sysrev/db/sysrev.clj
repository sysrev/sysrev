(ns sysrev.db.sysrev
  (:require
   [sysrev.db.core :refer [do-query do-execute do-transaction]]
   [sysrev.db.articles :as articles]
   [sysrev.db.users :as users]
   [sysrev.util :refer [mapify-by-id mapify-group-by-id]]
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

(defn sr-summary []
  {:articles (sr-article-count)
   :labels (sr-label-counts)
   :conflicts (sr-conflict-counts)})
