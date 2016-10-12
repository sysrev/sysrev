(ns sysrev.db.predict
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction scorify-article]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn predict-label-value [article-id criteria-id sim-version-id]
  (let [sims-fn
        (fn [above?]
          (let [other-id (if above? :lo_id :hi_id)
                this-id (if above? :hi_id :lo_id)]
            (-> (select [other-id :article_id] [:similarity :distance] :ac.answer)
                (from [:article_similarity :s])
                (join [:article_criteria :ac]
                      [:= :ac.article_id other-id])
                (where
                 [:and
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
                       (sort-by :sim >)))
        yes (future (first (answer-sims true)))
        no (future (first (answer-sims false)))]
    {true @yes false @no}))

(defn cache-label-similarities [project-id criteria-id sim-version-id]
  (let [article-ids (->> (-> (select :article_id)
                             (from :article)
                             (where [:= :project_id project-id])
                             do-query)
                         (map :article_id))]
    (doseq [article-id article-ids]
      (let [sims (predict-label-value article-id criteria-id sim-version-id)]
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
                      :sim_article_id (get-in sims [answer :article-id])
                      :max_sim (get-in sims [answer :sim])}))))
            do-execute)))
    true))
