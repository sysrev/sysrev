(ns sysrev.predict.report
  (:require
   [sysrev.util :refer [map-values integerify-map-keys uuidify-map-keys]]
   [sysrev.db.core :refer
    [do-query do-execute sql-now time-to-string to-jsonb
     with-query-cache clear-predict-cache]]
   [sysrev.db.queries :as q]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [sysrev.predict.core :refer []]))

(defn estimate-articles-with-value
  "Calculates an estimate of the number of project articles for which
  `label-id` will have a value of `true`."
  [predict-run-id label-id]
  (-> (select :%sum.lp.val :%count.lp.val)
      (from [:label-predicts :lp])
      (where [:and
              [:= :predict-run-id predict-run-id]
              [:= :label-id label-id]
              [:= :stage 1]])
      do-query
      first))

(defn article-count-by-label-prob [predict-run-id label-id cutoff greater?]
  (-> (select :%count.*)
      (from [:label-predicts :lp])
      (where [:and
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
  (-> (select :%count.*)
      (from [:label-predicts :lp])
      (join [:predict-run :pr]
            [:= :pr.predict-run-id :lp.predict-run-id])
      (where [:and
              [:= :lp.predict-run-id predict-run-id]
              [:= :lp.label-id label-id]
              [:= :lp.stage 1]
              (let [label-exists
                    [:exists
                     (-> (select :*)
                         (from [:article-label :al])
                         (where [:and
                                 [:= :al.article-id :lp.article-id]
                                 [:= :al.label-id label-id]
                                 [:!= :al.answer nil]
                                 (if (nil? answer)
                                   true
                                   [:= :al.answer (to-jsonb answer)])
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
         (q/query-predict-run-by-id predict-run-id [:*])
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
                  {(Math/round (* prob 100))
                   (article-count-by-label-prob
                    predict-run-id label-id prob true)}))
               (apply merge))}
         {:confidence
          (->> confidence-probs
               (map
                (fn [prob]
                  {(Math/round (* prob 100))
                   (article-count-by-label-prob
                    predict-run-id label-id (- 1.0 prob) false)}))
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
  (when force-update
    (clear-predict-cache))
  (with-query-cache
    [:predict :predict-run predict-run-id :summary]
    (let [summary (-> (q/query-predict-run-by-id predict-run-id [:meta])
                      :meta :summary)]
      (if (and summary (not force-update))
        (-> summary
            integerify-map-keys
            uuidify-map-keys)
        (let [new-summary
              (->> (-> (select :%distinct.label-id)
                       (from :label-predicts)
                       (where [:= :predict-run-id predict-run-id])
                       do-query)
                   (map :label-id)
                   (pmap (fn [label-id]
                           {label-id (predict-summary-for-label
                                      predict-run-id label-id)}))
                   doall
                   (apply merge))]
          (-> (sqlh/update :predict-run)
              (sset {:meta (to-jsonb {:summary new-summary})})
              (where [:= :predict-run-id predict-run-id])
              do-execute)
          new-summary)))))
