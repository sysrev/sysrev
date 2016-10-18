(ns sysrev.predict.core
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction sql-now]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn create-predict-version
  "Adds a new predict_version entry to database."
  [note]
  (-> (insert-into :predict_version)
      (values [{:note note}])
      do-execute))

(defn update-predict-version
  "Marks a predict_version entry as being updated at current time."
  [predict-version-id]
  (-> (sqlh/update :predict_version)
      (sset {:update_time (sql-now)})
      (where [:= :predict_version_id predict-version-id])
      do-execute))

(defn get-predict-run
  "Gets a predict_run entry by primary key."
  [predict-run-id]
  (-> (select :*)
      (from :predict_run)
      (where [:= :predict_run_id predict-run-id])
      do-query
      first))

(defn latest-predict-run
  "Gets the most recent predict_run entry matching the arguments."
  ([project-id]
   (-> (select :*)
       (from :predict_run)
       (where [:= :project_id project-id])
       (order-by [:create_time :desc])
       (limit 1)
       do-query
       first))
  ([project-id sim-version-id predict-version-id]
   (-> (select :*)
       (from :predict_run)
       (where [:and
               [:= :project_id project-id]
               [:= :sim_version_id sim-version-id]
               [:= :predict_version_id predict-version-id]])
       (order-by [:create_time :desc])
       (limit 1)
       do-query
       first)))

(defn create-predict-run
  "Adds a new predict_run entry to the database, and returns the entry."
  [project-id sim-version-id predict-version-id]
  (-> (insert-into :predict_run)
      (values [{:project_id project-id
                :sim_version_id sim-version-id
                :predict_version_id predict-version-id}])
      do-execute)
  (latest-predict-run project-id sim-version-id predict-version-id))

(defn label-value-sims
  "Creates a map of (label-value -> max-similarity) for each possible value of 
  `criteria-id`, where max-similarity is the similarity of `article-id` to the
  closest labeled article whose value for `criteria-id` is label-value."
  [article-id criteria-id predict-run]
  (let [n-closest 1
        sim-version-id (:sim_version_id predict-run)
        input-time (:input_time predict-run)
        sims-fn
        (fn [above?]
          (let [other-id (if above? :lo_id :hi_id)
                this-id (if above? :hi_id :lo_id)]
            (-> (select [other-id :article_id] [:similarity :distance] :ac.answer)
                (from [:article_similarity :s])
                (join [:article_criteria :ac]
                      [:= :ac.article_id other-id])
                (where
                 [:and
                  [:> :similarity 0.01]
                  [:!= other-id this-id]
                  [:= :s.sim_version_id sim-version-id]
                  [:= this-id article-id]
                  [:= :ac.criteria_id criteria-id]
                  [:!= :ac.confirm_time nil]
                  [:<= :ac.confirm_time input-time]])
                do-query)))
        [aboves belows] (pvalues (sims-fn true) (sims-fn false))
        sims (->> (concat aboves belows)
                  (group-by :article_id))
        answer-sims (fn [answer]
                      (->>
                       (keys sims)
                       (filter
                        (fn [article-id]
                          (let [entries (get sims article-id)]
                            (> (->> entries
                                    (filter #(= (:answer %) answer))
                                    count)
                               (->> entries
                                    (filter #(not= (:answer %) answer))
                                    count)))))
                       (map (fn [article-id]
                              {:article-id article-id
                               :sim (->> (get sims article-id)
                                         (filter #(= (:answer %) answer))
                                         first
                                         :distance
                                         (- 1.0))}))
                       (sort-by :sim >)
                       (take n-closest)
                       (map :sim)
                       (apply +)
                       (* (/ 1.0 n-closest))))
        [yes no] (pvalues (answer-sims true) (answer-sims false))]
    {true yes false no}))

(defn cache-label-similarities [predict-run-id criteria-id]
  (let [predict-run (get-predict-run predict-run-id)
        project-id (:project_id predict-run)
        article-ids
        (->> (-> (select :article_id)
                 (from [:article :a])
                 (where
                  [:and
                   [:= :project_id project-id]
                   [:not
                    [:exists
                     (-> (select :*)
                         (from [:label_similarity :ls])
                         (where
                          [:and
                           [:= :ls.article_id :a.article_id]
                           [:= :ls.predict_run_id predict-run-id]
                           [:= :ls.criteria_id criteria-id]]))]]])
                 do-query)
             (map :article_id))]
    (->> article-ids
         (pmap
          (fn [article-id]
            (let [sims (label-value-sims
                        article-id criteria-id predict-run)]
              (println (format "storing for %d: %s" article-id (pr-str sims)))
              (-> (insert-into :label_similarity)
                  (values
                   (->> [true false]
                        (map
                         (fn [answer]
                           {:predict_run_id predict-run-id
                            :article_id article-id
                            :criteria_id criteria-id
                            :answer answer
                            :max_sim (get sims answer)}))))
                  do-execute))))
         doall)
    true))

(defn relative-label-similarity [predict-run-id criteria-id article-id]
  (let [sims (->> (-> (select :answer :max_sim)
                      (from :label_similarity)
                      (where [:and
                              [:= :predict_run_id predict-run-id]
                              [:= :criteria_id criteria-id]
                              [:= :article_id article-id]])
                      do-query)
                  (group-by :answer)
                  (map-values first))
        true-sim (get-in sims [true :max_sim])
        false-sim (get-in sims [false :max_sim])
        sum (+ true-sim false-sim)]
    (if (zero? sum)
      0.0
      (/ true-sim sum))))

(defn cache-relative-similarities [predict-run-id criteria-id]
  (let [stage 0
        predict-run (get-predict-run predict-run-id)
        project-id (:project_id predict-run)
        article-ids
        (->> (-> (select :article_id)
                 (from [:article :a])
                 (where
                  [:and
                   [:= :project_id project-id]
                   [:not
                    [:exists
                     (-> (select :*)
                         (from [:label_predicts :lp])
                         (where
                          [:and
                           [:= :lp.article_id :a.article_id]
                           [:= :lp.predict_run_id predict-run-id]
                           [:= :lp.criteria_id criteria-id]
                           [:= :lp.stage stage]]))]]])
                 do-query)
             (map :article_id))
        sql-entries
        (->> article-ids
             (pmap
              (fn [article-id]
                (let [rsim (relative-label-similarity
                            predict-run-id criteria-id article-id)]
                  (println (format "calculated rsim %.4f" rsim))
                  {:predict_run_id predict-run-id
                   :article_id article-id
                   :criteria_id criteria-id
                   :stage stage
                   :val rsim})))
             doall)]
    (->> sql-entries
         (partition-all 100)
         (pmap
          (fn [sql-group]
            (println (format "storing %d entries - %d + [...]"
                             (-> sql-group count)
                             (-> sql-group first :article_id)))
            (-> (insert-into :label_predicts)
                (values (vec sql-group))
                do-execute)))
         doall)
    true))

(defn partition-predict-vals [predict-run-id stage criteria-id n-groups]
  (let [predict-run (get-predict-run predict-run-id)
        pvals
        (->>
         (-> (select :lp.article_id :lp.val :ac.answer)
             (from [:label_predicts :lp])
             (join [:article_criteria :ac]
                   [:= :ac.article_id :lp.article_id])
             (where
              [:and
               [:!= :ac.confirm_time nil]
               [:<= :ac.confirm_time (:input_time predict-run)]
               [:!= :ac.answer nil]
               [:= :ac.criteria_id criteria-id]
               [:= :lp.predict_run_id predict-run-id]
               [:= :lp.criteria_id criteria-id]
               [:= :lp.stage stage]])
             do-query)
         (group-by :article_id))
        article-answer
        (fn [article-id]
          (let [entries (get pvals article-id)
                answers (->> entries (map :answer) distinct)
                answer-counts
                (->>
                 answers
                 (map
                  (fn [answer]
                    {:answer answer
                     :count (->> entries
                                 (filter #(= (:answer %) answer))
                                 count)})))]
            (->> answer-counts
                 (sort-by :count >)
                 first
                 :answer)))
        groups
        (->> pvals
             (map-values
              (fn [entries]
                (let [article-id (-> entries first :article_id)]
                  {:article_id article-id
                   :val (-> entries first :val)
                   :answer (article-answer article-id)})))
             vals
             (sort-by :val <)
             (partition-all (-> (/ (count pvals) n-groups)
                                Math/ceil
                                int)))]
    groups))

(defn predict-label [labeled-rsims test-rsim k-nearest]
  (let [near-labels
        (->> labeled-rsims
             (sort-by #(Math/abs (- test-rsim (:val %))) <)
             (take k-nearest))
        answers (->> near-labels (map :answer) distinct)
        answer-probs
        (->>
         answers
         (map
          (fn [answer]
            {answer
             (if (empty? near-labels)
               0.0
               (double
                (/ (->> near-labels
                        (filter #(= (:answer %) answer))
                        count)
                   (count near-labels))))}))
         (apply merge))]
    answer-probs))

(defn cache-predict-probs [predict-run-id criteria-id k-nearest]
  (let [predict-run (get-predict-run predict-run-id)
        project-id (:project_id predict-run)
        [[labeled-rsims] all-rsims]
        (pvalues
         (partition-predict-vals
          predict-run-id 0 criteria-id 1)
         (-> (select :lp.article_id :lp.val)
             (from [:label_predicts :lp])
             (join [:article :a]
                   [:= :a.article_id :lp.article_id])
             (where
              [:and
               [:= :a.project_id project-id]
               [:= :lp.predict_run_id predict-run-id]
               [:= :lp.criteria_id criteria-id]
               [:= :lp.stage 0]])
             do-query))
        k-nearest (min k-nearest
                       (quot (count labeled-rsims) 10))
        sql-entries
        (->> all-rsims
             (pmap
              (fn [entry]
                (let [answer-probs
                      (predict-label
                       labeled-rsims (:val entry) k-nearest)
                      prob (or (get answer-probs true) 0.0)]
                  (println (format "predicted %.4f" prob))
                  {:predict_run_id predict-run-id
                   :article_id (:article_id entry)
                   :criteria_id criteria-id
                   :stage 1
                   :val prob})))
             doall)]
    (-> (delete-from :label_predicts)
        (where [:and
                [:= :predict_run_id predict-run-id]
                [:= :criteria_id criteria-id]
                [:= :stage 1]])
        do-execute)
    (->> sql-entries
         (partition-all 100)
         (pmap
          (fn [sql-group]
            (println (format "storing %d entries - %d + [...]"
                             (-> sql-group count)
                             (-> sql-group first :article_id)))
            (-> (insert-into :label_predicts)
                (values (vec sql-group))
                do-execute)))
         doall)
    true))

(defn do-predict-run [predict-run-id criteria-id]
  (let [k-nearest 50]
    (cache-label-similarities predict-run-id criteria-id)
    (cache-relative-similarities predict-run-id criteria-id)
    (cache-predict-probs predict-run-id criteria-id k-nearest)))
