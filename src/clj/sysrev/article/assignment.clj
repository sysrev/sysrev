(ns sysrev.article.assignment
  (:require [clojure.math.numeric-tower :as math]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [merge-where]]
            [sysrev.db.core :as db :refer [do-query with-project-cache]]
            [honeysql.helpers :as sqlh :refer [select limit from join insert-into values where order-by
                                               having left-join offset]]
            [sysrev.db.queries :as q]
            [sysrev.article.core :as article]
            [sysrev.project.core :as project]
            [sysrev.util :as util :refer [in? map-values index-by]]))

(defn user-confirmed-today-count [project-id user-id]
  (-> (q/select-project-article-labels project-id true [:al.article-id])
      (q/filter-label-user user-id)
      (merge-where [:=
                    (sql/call "date_trunc" "day" :al.confirm-time)
                    (sql/call "date_trunc" "day" :%now)])
      (->> do-query (map :article-id) distinct count)))

(defn query-ideal-fallback-article [project-id user-id]
  "this is an emergency case where other ideal article methods fail.
  Picks a random unlabeled article, or single labeled article"
  (-> (select :article-id)
      (from
        [(->
           (select :ar.article-id)(from [:article :ar])
           (where [:and
                   [:= :project-id project-id]
                   [:= :ar.enabled true]
                   [:exists
                    (-> (select 1)
                        (from [:article_label :al])
                        (where [:= :al.article-id :ar.article-id])
                        (sqlh/group :ar.article-id)
                        (having [:and
                                 [:<= :%count.%distinct.user-id 1]
                                 [:= (sql/call :max (sql/raw ["CASE WHEN user_id = " user-id "THEN 1 ELSE 0 END"])) 0]]))]])

           (limit 30)) :sa])
      (order-by :%random)
      (limit 1)
      do-query
      (first)))

(defn- query-ideal-unlimited-single [project-id user-id]
  (->
    (select :article-id)
    (from
      [(->
         (select :ar.article-id)
         (from [:article :ar])
         (where [:and
                 [:= :project-id project-id]
                 [:= :ar.enabled true]
                 [:not [:exists (-> (select 1)(from [:article-label :al])
                                    (where [:and
                                            [:= :al.article-id :ar.article-id]
                                            [:= :user-id user-id]]))]]])
         (limit 10)) :ul])
    (order-by :%random)
    (limit 1)
    do-query
    (first)))

(defn- query-ideal-unlimited-double [project-id user-id]
  (->
    (select :article-id)
    (from
      [(->
         (select :ar.article-id)
         (from [:article :ar])
         (where [:and
                 [:= :project-id project-id]
                 [:= :ar.enabled true]
                 [:exists (-> (select 1)(from [:article-label :al])(where [:= :al.article-id :ar.article-id]))]
                 [:not [:exists (-> (select 1)(from [:article-label :al])
                                    (where [:and [:= :al.article-id :ar.article-id][:= :user-id user-id]]))]]])
         (limit 10)) :ul])
    (order-by :%random)
    (limit 1)
    do-query
    (first)))

(defn query-ideal-unlimited-article [project-id user-id & {:keys [second-prob] :or {second-prob 0.5}}]
  "unlimited setting means each reviewer should review every article.
  Just pick an article the given user hasn't been reviewed by the given user yet."
  (let [single-unlimited (query-ideal-unlimited-single project-id user-id)
        double-unlimited (query-ideal-unlimited-double project-id user-id)]
    (cond (and single-unlimited double-unlimited) (if (<= (util/crypto-rand) second-prob)
                                                    double-unlimited single-unlimited)
          single-unlimited single-unlimited
          double-unlimited double-unlimited
          :else nil)))

(defn query-ideal-single-article [project-id user-id]
  (-> (select :article-id)
      (from
        [(->
           (select :ar.article-id)(from [:article :ar])
           (where [:and
                   [:= :project-id project-id]
                   [:= :ar.enabled true]
                   [:exists
                    (-> (select 1)
                        (from [:article_label :al])
                        (where [:= :al.article-id :ar.article-id])
                        (sqlh/group :ar.article-id)
                        (having [:and
                                 [:= :%count.%distinct.user-id 1]
                                 [:= (sql/call :max (sql/raw ["CASE WHEN user_id = " user-id "THEN 1 ELSE 0 END"])) 0]]))]])

           (limit 30)) :sa])
      (order-by :%random)
      (limit 1)
      do-query
      (first)))

(defn query-ideal-unlabeled-article [project-id]
  (let
    [pred-run (q/project-latest-predict-run-id project-id)]
    (if pred-run
      (let [res
            (-> (select :article-id :val)
                (from
                  [(-> (select :ar.article-id :lp.val) (from [:article :ar])
                       (left-join [:label-predicts :lp] [:= :ar.article-id :lp.article-id])
                       (where [:and
                               [:= :project-id project-id]
                               [:= :ar.enabled true]
                               [:= :predict-run-id (q/project-latest-predict-run-id project-id)]
                               [:not [:exists (->
                                                (select 1)
                                                (from [:article-label :al])
                                                (where [:= :al.article-id :ar.article-id]))]]])
                       (limit 100)) :preds])
                (order-by :%random)
                (limit 30)
                do-query)]
        (if (empty? res)
          nil
          (do
            (reduce (fn [a b] (if (> (Math/abs (- (or (:val a) 0.0) 0.5))
                                 (Math/abs (- (or (:val b) 0.0) 0.5))) b a)) res))))
      (-> (select :article-id)
          (from
            [(-> (select :ar.article-id) (from [:article :ar])
                 (where [:and
                         [:= :project-id project-id]
                         [:= :ar.enabled true]
                         [:not [:exists (->
                                          (select 1)
                                          (from [:article-label :al])
                                          (where [:= :al.article-id :ar.article-id]))]]])
                 (limit 30)) :sq])
          (order-by :%random)
          (limit 1)
          do-query
          (first)))))

(defn get-user-label-task [project-id user-id]
  (let [{:keys [second-review-prob unlimited-reviews]
         :or   {second-review-prob 0.5
                unlimited-reviews  false}} (project/project-settings project-id)
        [unlimited single-label unlabeled today-count]
        (pvalues (when unlimited-reviews      (query-ideal-unlimited-article project-id user-id :second-prob second-review-prob))
                 (when-not unlimited-reviews  (query-ideal-single-article project-id user-id))
                 (when-not unlimited-reviews  (query-ideal-unlabeled-article project-id))
                 (user-confirmed-today-count project-id user-id))
        [article status]
        (cond unlimited-reviews             [unlimited :unlimited]
              (and single-label unlabeled)  (if (<= (util/crypto-rand) second-review-prob)
                                              [single-label :single] [unlabeled :unreviewed])
              single-label                  [single-label :single]
              unlabeled                     [unlabeled :unreviewed]
              :else                         (when-let [fallback (query-ideal-fallback-article project-id user-id)]
                                              [fallback :single]))]
    (when (and article status)
      {:article-id (:article-id article)
       :today-count today-count})))