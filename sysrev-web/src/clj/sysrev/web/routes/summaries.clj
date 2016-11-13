(ns sysrev.web.routes.summaries
  (:require
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [clojure-csv.core :refer [write-csv]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction *active-project*]]
   [sysrev.db.project :refer [project-article-count project-criteria]]
   [sysrev.db.labels :refer [all-label-conflicts all-overall-labels]]
   [sysrev.db.users :refer [get-member-summaries]]
   [sysrev.predict.core :refer [latest-predict-run]]
   [sysrev.predict.report :refer [predict-summary]]
   [sysrev.util :refer [map-values in?]]))

(defn project-conflict-counts []
  (let [conflicts (all-label-conflicts)
        resolved? (fn [[aid labels :as conflict]]
                    (> (count labels) 2))
        n-total (count conflicts)
        n-pending (->> conflicts (remove resolved?) count)
        n-resolved (- n-total n-pending)]
    {:total n-total
     :pending n-pending
     :resolved n-resolved}))

(defn project-label-counts []
  (let [labels (all-overall-labels)
        counts (->> labels vals (map count))]
    {:any (count labels)
     :single (->> counts (filter #(= % 1)) count)
     :double (->> counts (filter #(= % 2)) count)
     :multi (->> counts (filter #(> % 2)) count)}))

(defn project-label-value-counts
  "Returns counts of [true, false, unknown] label values for each criteria.
  true/false can be counted by the labels saved by all users.
  'unknown' values are counted when the user has set a value for at least one
  label on the article."
  []
  (let [entries
        (->>
         (-> (select :ac.article-id :criteria-id :user-id :answer)
             (from [:article-criteria :ac])
             (join [:article :a] [:= :a.article-id :ac.article-id])
             (where [:and
                     [:= :a.project-id *active-project*]
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
        criteria-ids (keys (project-criteria *active-project*))
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

(defn project-summary [& [project-id]]
  (let [project-id (or project-id *active-project*)]
    (binding [*active-project* project-id]
      (let [[predict articles labels label-values conflicts users]
            (pvalues (predict-summary
                      (:predict-run-id (latest-predict-run project-id)))
                     (project-article-count project-id)
                     (project-label-counts)
                     (project-label-value-counts)
                     (project-conflict-counts)
                     (get-member-summaries))]
        {:project-id project-id
         :users users
         :stats {:articles articles
                 :labels labels
                 :label-values label-values
                 :conflicts conflicts
                 :predict predict}
         :criteria (project-criteria project-id)}))))

(defn get-project-summaries
  "Returns a sequence of summary maps for every project."
  []
  (let [projects
        (->> (-> (select :*)
                 (from :project)
                 do-query)
             (group-by :project-id)
             (map-values first))
        admins
        (->> (-> (select :u.user-id :u.email :m.permissions :m.project-id)
                 (from [:project-member :m])
                 (join [:web-user :u]
                       [:= :u.user-id :m.user-id])
                 do-query)
             (group-by :project-id)
             (map-values
              (fn [pmembers]
                (->> pmembers
                     (filter #(in? (:permissions %) "admin"))
                     (mapv #(dissoc % :project-id))))))]
    (->> projects
         (map-values
          #(assoc % :admins
                  (get admins (:project-id %) []))))))
