(ns sysrev.predict.report
  (:require
   [sysrev.util :refer [map-values integerify-map-keys]]
   [sysrev.db.core :refer
    [do-query do-execute sql-now time-to-string to-jsonb]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [sysrev.predict.core :refer [get-predict-run]]))

(defn estimate-articles-with-value
  "Calculates an estimate of the number of project articles for which
  `criteria-id` will have a value of `true`."
  [predict-run-id criteria-id]
  (-> (select :%sum.lp.val :%count.lp.val)
      (from [:label-predicts :lp])
      (where [:and
              [:= :predict-run-id predict-run-id]
              [:= :criteria-id criteria-id]
              [:= :stage 1]])
      do-query
      first))

(defn article-count-by-label-prob [predict-run-id criteria-id cutoff greater?]
  (-> (select :%count.*)
      (from [:label-predicts :lp])
      (where [:and
              [:= :predict-run-id predict-run-id]
              [:= :criteria-id criteria-id]
              [:= :stage 1]
              (if greater?
                [:>= :val cutoff]
                [:<= :val cutoff])])
      do-query
      first
      :count))

(defn predict-run-article-count [predict-run-id criteria-id & [labeled? answer]]
  (-> (select :%count.*)
      (from [:label-predicts :lp])
      (join [:predict-run :pr]
            [:= :pr.predict-run-id :lp.predict-run-id])
      (where [:and
              [:= :lp.predict-run-id predict-run-id]
              [:= :lp.criteria-id criteria-id]
              [:= :lp.stage 1]
              (let [label-exists
                    [:exists
                     (-> (select :*)
                         (from [:article-criteria :ac])
                         (where [:and
                                 [:= :ac.article-id :lp.article-id]
                                 [:= :ac.criteria-id criteria-id]
                                 [:!= :ac.answer nil]
                                 (if (nil? answer)
                                   true
                                   [:= :ac.answer answer])
                                 [:!= :ac.confirm-time nil]
                                 [:<= :ac.confirm-time :pr.input-time]]))]]
                (case labeled?
                  true label-exists
                  false [:not label-exists]
                  true))])
      do-query
      first
      :count))

(defn predict-summary-for-criteria [predict-run-id criteria-id]
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
         (get-predict-run predict-run-id)
         (predict-run-article-count predict-run-id criteria-id nil)
         (predict-run-article-count predict-run-id criteria-id true)
         (predict-run-article-count predict-run-id criteria-id true true)
         (predict-run-article-count predict-run-id criteria-id true false)
         (predict-run-article-count predict-run-id criteria-id false)
         (:sum (estimate-articles-with-value predict-run-id criteria-id))
         {:confidence
          (->> confidence-probs
               (map
                (fn [prob]
                  {(Math/round (* prob 100))
                   (article-count-by-label-prob
                    predict-run-id criteria-id prob true)}))
               (apply merge))}
         {:confidence
          (->> confidence-probs
               (map
                (fn [prob]
                  {(Math/round (* prob 100))
                   (article-count-by-label-prob
                    predict-run-id criteria-id (- 1.0 prob) false)}))
               (apply merge))})]
    {:update-time (-> predict-run :create-time time-to-string (str " UTC"))
     :counts {:total total-count
              :labeled labeled-count
              :include-label include-count
              :exclude-label exclude-count
              :unlabeled unlabeled-count}
     :estimate estimate
     :include include
     :exclude exclude}))

(defn predict-summary [predict-run-id & [force-update]]
  (let [summary (-> (select :meta)
                    (from :predict-run)
                    (where [:= :predict-run-id predict-run-id])
                    do-query first :meta :summary)]
    (if (and summary (not force-update))
      (integerify-map-keys summary)
      (let [new-summary
            (->> (-> (select :%distinct.criteria-id)
                     (from :label-predicts)
                     (where [:= :predict-run-id predict-run-id])
                     do-query)
                 (map :criteria-id)
                 (pmap (fn [criteria-id]
                         {criteria-id (predict-summary-for-criteria
                                       predict-run-id criteria-id)}))
                 doall
                 (apply merge))]
        (-> (sqlh/update :predict-run)
            (sset {:meta (to-jsonb {:summary new-summary})})
            (where [:= :predict-run-id predict-run-id])
            do-execute)
        new-summary))))
