(ns sysrev.label.answer
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.article.core :as article]
            [sysrev.label.core :as l]
            [sysrev.util :as util]
            [sysrev.shared.util :as su :refer [map-values in?]]
            [sysrev.shared.labels :refer [cleanup-label-answer]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defn label-answer-valid? [label-id answer]
  (let [label (get (l/all-labels-cached) label-id)]
    (case (:value-type label)
      "boolean"
      (when (in? [true false nil] answer)
        {label-id answer})
      "categorical"
      (cond (nil? answer)
            {label-id answer}
            (sequential? answer)
            (let [allowed (-> label :definition :all-values)]
              (when (every? (in? allowed) answer)
                {label-id answer}))
            :else nil)
      ;; TODO: check that answer value matches label regex
      "string" (when (coll? answer)
                 (let [filtered (->> answer
                                     (filter string?)
                                     (filterv not-empty))]
                   (cond (empty? filtered)
                         {label-id nil}
                         (every? string? filtered)
                         {label-id filtered}
                         :else nil)))
      {label-id answer})))

(defn label-answer-inclusion [label-id answer]
  (let [label (get (l/all-labels-cached) label-id)
        ivals (-> label :definition :inclusion-values)]
    (case (:value-type label)
      "boolean"
      (cond
        (empty? ivals) nil
        (nil? answer) nil
        :else (boolean (in? ivals answer)))
      "categorical"
      (cond
        (empty? ivals) nil
        (nil? answer) nil
        (empty? answer) nil
        :else (boolean (some (in? ivals) answer)))
      nil)))

(defn label-possible-values [label-id]
  (let [label (get (l/all-labels-cached) label-id)]
    (case (:value-type label)
      "boolean"
      [true false]
      "categorical"
      (-> label :definition :all-values)
      nil)))

(defn filter-valid-label-values [label-values]
  (->> label-values
       (mapv
        (fn [[label-id answer]]
          (label-answer-valid? label-id answer)))
       (remove nil?)
       (apply merge)))

(defn resolve-article-answers
  "Create new article_resolve entry to record a user resolving answers
  for an article at current time and for current consensus labels."
  [article-id user-id & {:keys [resolve-time label-ids]}]
  (with-transaction
    (let [project-id (article/article-project-id article-id)
          label-ids (or label-ids
                        (project/project-consensus-label-ids project-id))
          resolve-time (or resolve-time (db/sql-now))]
      (-> (insert-into :article-resolve)
          (values [{:article-id article-id
                    :user-id user-id
                    :resolve-time resolve-time
                    :label-ids (db/to-jsonb (vec label-ids))}])
          (returning :*)
          do-query))))

;; TODO: check that all required labels are answered
(defn set-user-article-labels
  "Set article-id for user-id with a map of label-values. imported? is
  vestigal, confirm? set to true will set the confirm_time to now,
  change? set to true will set the updated_time to now, resolve will
  be set to resolve? The format of label-values is {<label-id-1>
  <value-1>, <label-id-2> <value-2>, ... , <label-id-n> <value-n>}.

  It should be noted that a label's confirm value must be set to true
  in order to be recognized as a labeled article in the rest of the
  app. This includes article summary graphs, compensations,
  etc. e.g. If confirm? is not to set true, then a user would not be
  compensated for that article."
  [user-id article-id label-values &
   {:keys [imported? confirm? change? resolve?]}]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (with-transaction
    (let [valid-values (->> label-values filter-valid-label-values)
          now (db/sql-now)
          project-id (:project-id (q/query-article-by-id article-id [:project-id]))
          current-entries
          (when change?
            (-> (q/select-article-by-id article-id [:al.*])
                (q/join-article-labels)
                (q/filter-label-user user-id)
                (->> (do-query)
                     (map #(-> %
                               (update :answer db/to-jsonb)
                               (update :imported boolean)
                               (dissoc :article-label-id :article-label-local-id))))))
          overall-label-id (project/project-overall-label-id project-id)
          confirm? (if imported?
                     (boolean (get valid-values overall-label-id))
                     confirm?)
          existing-label-ids
          (-> (q/select-article-by-id article-id [:al.label-id])
              (q/join-article-labels)
              (q/filter-label-user user-id)
              (->> do-query (map :label-id)))
          new-label-ids
          (->> (keys valid-values)
               (remove (in? existing-label-ids)))
          new-entries
          (->> new-label-ids
               (map (fn [label-id]
                      (let [label (get (l/all-labels-cached) label-id)
                            answer (->> (get valid-values label-id)
                                        (cleanup-label-answer label))
                            _ (assert (label-answer-valid? label-id answer))
                            inclusion (label-answer-inclusion label-id answer)]
                        (cond->
                            {:label-id label-id
                             :article-id article-id
                             :user-id user-id
                             :answer (db/to-jsonb answer)
                             :added-time now
                             :updated-time now
                             :imported (boolean imported?)
                             :resolve (boolean resolve?)
                             :inclusion inclusion}
                          confirm? (merge {:confirm-time now}))))))]
      (doseq [label-id existing-label-ids]
        (when (contains? valid-values label-id)
          (let [label (get (l/all-labels-cached) label-id)
                answer (->> (get valid-values label-id)
                            (cleanup-label-answer label))
                _ (assert (label-answer-valid? label-id answer))
                inclusion (label-answer-inclusion label-id answer)]
            (-> (sqlh/update :article-label)
                (sset (cond->
                          {:answer (db/to-jsonb answer)
                           :updated-time now
                           :imported (boolean imported?)
                           :resolve (boolean resolve?)
                           :inclusion inclusion}
                        confirm? (merge {:confirm-time now})))
                (where [:and
                        [:= :article-id article-id]
                        [:= :user-id user-id]
                        [:= :label-id label-id]])
                do-execute))))
      (when-not (empty? new-entries)
        (-> (insert-into :article-label)
            (values new-entries)
            do-execute))
      (when (and change? (not-empty current-entries))
        (-> (insert-into :article-label-history)
            (values current-entries)
            do-execute))
      (when resolve?
        (resolve-article-answers article-id user-id :resolve-time now))
      (db/clear-project-cache project-id)
      true)))

;; FIX: can inclusion-values be changed with existing answers?
;;      if yes, need to run this.
;;      if no, can delete this.
(defn update-label-answer-inclusion [label-id]
  (with-transaction
    (let [entries (-> (select :article-label-id :answer)
                      (from :article-label)
                      (where [:= :label-id label-id])
                      do-query)]
      (->> entries
           (map
            (fn [{:keys [article-label-id answer]}]
              (let [inclusion (label-answer-inclusion label-id answer)]
                (-> (sqlh/update :article-label)
                    (sset {:inclusion inclusion})
                    (where [:= :article-label-id article-label-id])
                    do-execute))))
           doall))))
