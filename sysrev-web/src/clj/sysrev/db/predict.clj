(ns sysrev.db.predict
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction sql-now]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn label-value-sims [article-id criteria-id sim-version-id]
  (let [n-closest 1
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
                  [:!= :ac.confirm_time nil]])
                do-query)))
        aboves (future (sims-fn true))
        belows (future (sims-fn false))
        sims (->> (concat @aboves @belows)
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
        yes (future (answer-sims true))
        no (future (answer-sims false))]
    {true @yes false @no}))

(defn relative-label-similarity [sim-version-id article-id criteria-id]
  (let [sims (->> (-> (select :answer :max_sim)
                      (from :label_similarity)
                      (where [:and
                              [:= :sim_version_id sim-version-id]
                              [:= :article_id article-id]
                              [:= :criteria_id criteria-id]])
                      do-query)
                  (group-by :answer)
                  (map-values first))
        true-sim (get-in sims [true :max_sim])
        false-sim (get-in sims [false :max_sim])
        sum (+ true-sim false-sim)]
    (if (zero? sum)
      0.0
      (/ true-sim sum))))

(defn cache-label-similarities [project-id criteria-id sim-version-id]
  (let [article-ids
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
                           [:= :ls.sim_version_id sim-version-id]
                           [:= :ls.criteria_id criteria-id]]))]]])
                 do-query)
             (map :article_id))]
    (->> article-ids
         (pmap
          (fn [article-id]
            (let [sims (label-value-sims
                        article-id criteria-id sim-version-id)]
              (println (format "storing for %d: %s" article-id (pr-str sims)))
              (-> (insert-into :label_similarity)
                  (values
                   (->> [true false]
                        (map
                         (fn [answer]
                           {:project_id project-id
                            :sim_version_id sim-version-id
                            :article_id article-id
                            :criteria_id criteria-id
                            :answer answer
                            ;; :sim_article_id (get-in sims [answer :article-id])
                            :sim_article_id -1
                            :max_sim (get sims answer)}))))
                  do-execute))))
         doall)
    true))

(defn create-predict-version [note]
  (-> (insert-into :predict_version)
      (values [{:note note}])
      do-execute))

(defn update-predict-version [predict-version-id]
  (-> (sqlh/update :predict_version)
      (sset {:update_time (sql-now)})
      (where [:= :predict_version_id predict-version-id])
      do-execute))

(defn cache-relative-similarities [project-id criteria-id sim-version-id]
  (let [stage 0
        predict-version-id 1
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
                           [:= :lp.sim_version_id sim-version-id]
                           [:= :lp.predict_version_id predict-version-id]
                           [:= :lp.criteria_id criteria-id]
                           [:= :lp.stage stage]]))]]])
                 do-query)
             (map :article_id))
        entries
        (->> article-ids
             (map
              (fn [article-id]
                {:sim_version_id sim-version-id
                 :predict_version_id predict-version-id
                 :article_id article-id
                 :criteria_id criteria-id
                 :stage stage
                 :val (relative-label-similarity
                       sim-version-id article-id criteria-id)})))]
    (doseq [e entries]
      (println (format "storing %d: %f" (:article_id e) (:val e)))
      (-> (insert-into :label_predicts)
          (values [e])
          do-execute))))
