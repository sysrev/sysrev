(ns sysrev.predict.core
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer [do-query do-execute sql-now]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn create-predict-version
  "Adds a new predict-version entry to database."
  [note]
  (-> (insert-into :predict-version)
      (values [{:note note}])
      do-execute))

(defn update-predict-version
  "Marks a predict-version entry as being updated at current time."
  [predict-version-id]
  (-> (sqlh/update :predict-version)
      (sset {:update-time (sql-now)})
      (where [:= :predict-version-id predict-version-id])
      do-execute))

(defn get-predict-run
  "Gets a predict-run entry by primary key."
  [predict-run-id]
  (-> (select :*)
      (from :predict-run)
      (where [:= :predict-run-id predict-run-id])
      do-query
      first))

(defn latest-predict-run
  "Gets the most recent predict-run entry matching the arguments."
  ([project-id]
   (-> (select :*)
       (from :predict-run)
       (where [:= :project-id project-id])
       (order-by [:create-time :desc])
       (limit 1)
       do-query
       first))
  ([project-id sim-version-id predict-version-id]
   (-> (select :*)
       (from :predict-run)
       (where [:and
               [:= :project-id project-id]
               [:= :sim-version-id sim-version-id]
               [:= :predict-version-id predict-version-id]])
       (order-by [:create-time :desc])
       (limit 1)
       do-query
       first)))

(defn latest-predict-run-id
  ":predict-run-id of (latest-predict-run ...)"
  ([project-id]
   (:predict-run-id
    (latest-predict-run project-id)))
  ([project-id sim-version-id predict-version-id]
   (:predict-run-id
    (latest-predict-run project-id sim-version-id predict-version-id))))

(defn create-predict-run
  "Adds a new predict-run entry to the database, and returns the entry."
  [project-id sim-version-id predict-version-id]
  (-> (insert-into :predict-run)
      (values [{:project-id project-id
                :sim-version-id sim-version-id
                :predict-version-id predict-version-id}])
      do-execute)
  (latest-predict-run project-id sim-version-id predict-version-id))

(defn label-value-sims
  "Creates a map of (label-value -> max-similarity) for each possible value of 
  `criteria-id`, where max-similarity is the similarity of `article-id` to the
  closest labeled article whose value for `criteria-id` is label-value."
  [article-id criteria-id predict-run]
  (let [n-closest 1
        sim-version-id (:sim-version-id predict-run)
        input-time (:input-time predict-run)
        sims-fn
        (fn [above?]
          (let [other-id (if above? :lo-id :hi-id)
                this-id (if above? :hi-id :lo-id)]
            (-> (select [other-id :article-id] [:similarity :distance] :ac.answer)
                (from [:article-similarity :s])
                (join [:article-criteria :ac]
                      [:= :ac.article-id other-id])
                (where
                 [:and
                  [:> :similarity 0.01]
                  [:!= other-id this-id]
                  [:= :s.sim-version-id sim-version-id]
                  [:= this-id article-id]
                  [:= :ac.criteria-id criteria-id]
                  [:!= :ac.confirm-time nil]
                  [:<= :ac.confirm-time input-time]])
                do-query)))
        [aboves belows] (pvalues (sims-fn true) (sims-fn false))
        sims (->> (concat aboves belows)
                  (group-by :article-id))
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
        project-id (:project-id predict-run)
        article-ids
        (->> (-> (select :article-id)
                 (from [:article :a])
                 (where
                  [:and
                   [:= :project-id project-id]
                   [:not
                    [:exists
                     (-> (select :*)
                         (from [:label-similarity :ls])
                         (where
                          [:and
                           [:= :ls.article-id :a.article-id]
                           [:= :ls.predict-run-id predict-run-id]
                           [:= :ls.criteria-id criteria-id]]))]]])
                 do-query)
             (map :article-id))]
    (->> article-ids
         (pmap
          (fn [article-id]
            (let [sims (label-value-sims
                        article-id criteria-id predict-run)]
              (println (format "storing for %d: %s" article-id (pr-str sims)))
              (-> (insert-into :label-similarity)
                  (values
                   (->> [true false]
                        (map
                         (fn [answer]
                           {:predict-run-id predict-run-id
                            :article-id article-id
                            :criteria-id criteria-id
                            :answer answer
                            :max-sim (get sims answer)}))))
                  do-execute))))
         doall)
    true))

(defn relative-label-similarity [predict-run-id criteria-id article-id]
  (let [sims (->> (-> (select :answer :max-sim)
                      (from :label-similarity)
                      (where [:and
                              [:= :predict-run-id predict-run-id]
                              [:= :criteria-id criteria-id]
                              [:= :article-id article-id]])
                      do-query)
                  (group-by :answer)
                  (map-values first))
        true-sim (get-in sims [true :max-sim])
        false-sim (get-in sims [false :max-sim])
        sum (+ true-sim false-sim)]
    (if (zero? sum)
      0.0
      (/ true-sim sum))))

(defn cache-relative-similarities [predict-run-id criteria-id]
  (let [stage 0
        predict-run (get-predict-run predict-run-id)
        project-id (:project-id predict-run)
        article-ids
        (->> (-> (select :article-id)
                 (from [:article :a])
                 (where
                  [:and
                   [:= :project-id project-id]
                   [:not
                    [:exists
                     (-> (select :*)
                         (from [:label-predicts :lp])
                         (where
                          [:and
                           [:= :lp.article-id :a.article-id]
                           [:= :lp.predict-run-id predict-run-id]
                           [:= :lp.criteria-id criteria-id]
                           [:= :lp.stage stage]]))]]])
                 do-query)
             (map :article-id))
        sql-entries
        (->> article-ids
             (pmap
              (fn [article-id]
                (let [rsim (relative-label-similarity
                            predict-run-id criteria-id article-id)]
                  (println (format "calculated rsim %.4f" rsim))
                  {:predict-run-id predict-run-id
                   :article-id article-id
                   :criteria-id criteria-id
                   :stage stage
                   :val rsim})))
             doall)]
    (->> sql-entries
         (partition-all 100)
         (pmap
          (fn [sql-group]
            (println (format "storing %d entries - %d + [...]"
                             (-> sql-group count)
                             (-> sql-group first :article-id)))
            (-> (insert-into :label-predicts)
                (values (vec sql-group))
                do-execute)))
         doall)
    true))

(defn partition-predict-vals [predict-run-id stage criteria-id n-groups]
  (let [predict-run (get-predict-run predict-run-id)
        pvals
        (->>
         (-> (select :lp.article-id :lp.val :ac.answer)
             (from [:label-predicts :lp])
             (join [:article-criteria :ac]
                   [:= :ac.article-id :lp.article-id])
             (where
              [:and
               [:!= :ac.confirm-time nil]
               [:<= :ac.confirm-time (:input-time predict-run)]
               [:!= :ac.answer nil]
               [:= :ac.criteria-id criteria-id]
               [:= :lp.predict-run-id predict-run-id]
               [:= :lp.criteria-id criteria-id]
               [:= :lp.stage stage]])
             do-query)
         (group-by :article-id))
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
                (let [article-id (-> entries first :article-id)]
                  {:article-id article-id
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
        project-id (:project-id predict-run)
        [[labeled-rsims] all-rsims]
        (pvalues
         (partition-predict-vals
          predict-run-id 0 criteria-id 1)
         (-> (select :lp.article-id :lp.val)
             (from [:label-predicts :lp])
             (join [:article :a]
                   [:= :a.article-id :lp.article-id])
             (where
              [:and
               [:= :a.project-id project-id]
               [:= :lp.predict-run-id predict-run-id]
               [:= :lp.criteria-id criteria-id]
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
                  {:predict-run-id predict-run-id
                   :article-id (:article-id entry)
                   :criteria-id criteria-id
                   :stage 1
                   :val prob})))
             doall)]
    (-> (delete-from :label-predicts)
        (where [:and
                [:= :predict-run-id predict-run-id]
                [:= :criteria-id criteria-id]
                [:= :stage 1]])
        do-execute)
    (->> sql-entries
         (partition-all 100)
         (pmap
          (fn [sql-group]
            (println (format "storing %d entries - %d + [...]"
                             (-> sql-group count)
                             (-> sql-group first :article-id)))
            (-> (insert-into :label-predicts)
                (values (vec sql-group))
                do-execute)))
         doall)
    true))

(defn do-predict-run [predict-run-id criteria-id]
  (let [k-nearest 50]
    (cache-label-similarities predict-run-id criteria-id)
    (cache-relative-similarities predict-run-id criteria-id)
    (cache-predict-probs predict-run-id criteria-id k-nearest)))
