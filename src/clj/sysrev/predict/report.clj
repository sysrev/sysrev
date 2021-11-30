(ns sysrev.predict.report
  (:require [honeysql.helpers :as sqlh :refer [select from where join merge-join]]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.predict.core]
            [sysrev.util :as util]))

(defn estimate-articles-with-value
  "Calculates an estimate of the number of project articles for which
  `label-id` will have a value of `true`."
  [predict-run-id label-id]
  (-> (select :%sum.lp.val :%count.lp.val)
      (from [:label-predicts :lp])
      (join [:article :a]
            [:= :a.article-id :lp.article-id])
      (where [:and
              [:= :a.enabled true]
              [:= :predict-run-id predict-run-id]
              [:= :label-id label-id]
              [:= :stage 1]])
      do-query
      first))

(defn article-count-by-label-prob [predict-run-id label-id cutoff greater?]
  (-> (select :%count.*)
      (from [:label-predicts :lp])
      (join [:article :a]
            [:= :a.article-id :lp.article-id])
      (where [:and
              [:= :a.enabled true]
              [:= :predict-run-id predict-run-id]
              [:= :label-id label-id]
              [:= :stage 1]
              (if greater?
                [:>= :val cutoff]
                [:<= :val cutoff])])
      do-query
      first
      :count))

(defn predict-run-article-count [predict-run-id label-id & [labeled? answer]]
  (-> (select :%count-distinct.lp.article-id)
      (from [:label-predicts :lp])
      (join [:predict-run :pr]
            [:= :pr.predict-run-id :lp.predict-run-id])
      (merge-join [:article :a]
                  [:= :a.article-id :lp.article-id])
      (where [:and
              [:= :a.enabled true]
              [:= :lp.predict-run-id predict-run-id]
              [:= :lp.label-id label-id]
              [:= :lp.stage 1]
              (let [label-exists
                    [:exists
                     (-> (select :*)
                         (from [:article-label :al])
                         (join [:article :a2]
                               [:= :a2.article-id :al.article-id])
                         (where [:and
                                 [:= :a2.enabled true]
                                 [:= :al.article-id :lp.article-id]
                                 [:= :al.label-id label-id]
                                 [:!= :al.answer nil]
                                 (if (nil? answer)
                                   true
                                   [:= :al.answer (db/to-jsonb answer)])
                                 [:!= :al.confirm-time nil]
                                 [:<= :al.confirm-time :pr.input-time]]))]]
                (case labeled?
                  true label-exists
                  false [:not label-exists]
                  true))])
      do-query
      first
      :count))

(defn predict-summary-for-label [predict-run-id label-id]
  (let [confidence-probs [0.5 0.75 0.9 0.95]
        [predict-run
         total-count
         labeled-count
         include-count
         exclude-count
         unlabeled-count
         estimate
         include
         exclude]
        (pvalues
         (q/find-one :predict-run {:predict-run-id predict-run-id})
         (predict-run-article-count predict-run-id label-id nil)
         (predict-run-article-count predict-run-id label-id true)
         (predict-run-article-count predict-run-id label-id true true)
         (predict-run-article-count predict-run-id label-id true false)
         (predict-run-article-count predict-run-id label-id false)
         (:sum (estimate-articles-with-value predict-run-id label-id))
         {:confidence
          (->> confidence-probs
               (map
                (fn [prob]
                  {(Math/round ^Double (* prob 100))
                   (article-count-by-label-prob
                    predict-run-id label-id prob true)}))
               (apply merge))}
         {:confidence
          (->> confidence-probs
               (map
                (fn [prob]
                  {(Math/round ^Double (* prob 100))
                   (article-count-by-label-prob
                    predict-run-id label-id (- 1.0 prob) false)}))
               (apply merge))})]
    {:update-time (-> predict-run :create-time util/write-time-string (str " UTC"))
     :counts {:total total-count
              :labeled labeled-count
              :include-label include-count
              :exclude-label exclude-count
              :unlabeled unlabeled-count}
     :estimate estimate
     :include include
     :exclude exclude}))

(defn update-predict-meta [project-id predict-run-id]
  (db/with-clear-project-cache project-id
    (let [overall-id (project/project-overall-label-id project-id)]
      (q/modify :predict-run {:predict-run-id predict-run-id}
                {:meta (db/to-jsonb (predict-summary-for-label
                                     predict-run-id overall-id))}))))

(defn predict-summary [predict-run-id]
  (q/find-one :predict-run {:predict-run-id predict-run-id} :meta))
