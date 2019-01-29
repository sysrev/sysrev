(ns sysrev.article.assignment
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.math.numeric-tower :as math]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.entity :as e]
            [sysrev.article.core :as article]
            [sysrev.db.project :as project]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.util :as u]
            [sysrev.shared.util :as su :refer [in? map-values]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defn query-assignment-articles [project-id & [predict-run-id]]
  (with-project-cache
    project-id [:label-values :saved :articles predict-run-id]
    (let [predict-run-id
          (or predict-run-id (q/project-latest-predict-run-id project-id))
          articles
          (-> (q/select-project-articles project-id [:a.article-id])
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (-> (first x)
                          (merge {:users [] :score 0.0}))))))
          scores
          (-> (q/select-project-articles project-id [:a.article-id])
              (q/with-article-predict-score predict-run-id)
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (let [score (or (-> x first :score) 0.0)]
                        {:score score})))))
          labels
          (-> (q/select-project-articles
               project-id [:a.article-id :al.user-id :al.confirm-time])
              (q/join-article-labels)
              (q/join-article-label-defs)
              (q/filter-valid-article-label nil)
              (->> do-query
                   (group-by :article-id)
                   (map-values
                    (fn [x]
                      (let [user-ids (->> x (map :user-id) distinct vec)
                            users-confirmed
                            (->> x (remove #(nil? (:confirm-time %)))
                                 (map :user-id) distinct vec)]
                        {:users user-ids
                         :users-confirmed users-confirmed})))))]
      (merge-with merge articles scores labels))))

(defn unlabeled-articles [project-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :unlabeled-articles predict-run-id]
    (->> (or articles (query-assignment-articles project-id predict-run-id))
         vals
         (filter #(= 0 (count (:users-confirmed %))))
         (map #(dissoc % :users-confirmed)))))

(defn single-labeled-articles [project-id self-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :single-labeled-articles self-id predict-run-id]
    (->> (or articles (query-assignment-articles project-id predict-run-id))
         vals
         (filter #(and (= 1 (count (:users %)))
                       (= 1 (count (:users-confirmed %)))
                       (not (in? (:users %) self-id))))
         (map #(dissoc % :users)))))

(defn fallback-articles [project-id self-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :fallback-articles self-id predict-run-id]
    (->> (or articles (query-assignment-articles project-id predict-run-id))
         vals
         (filter #(and (< (count (:users-confirmed %)) 2)
                       (not (in? (:users %) self-id))))
         (map #(dissoc % :users)))))

(defn unlimited-articles [project-id self-id & [predict-run-id articles]]
  (with-project-cache
    project-id [:label-values :saved :unlimited-articles self-id predict-run-id]
    (->> (or articles (query-assignment-articles project-id predict-run-id))
         vals
         (filter #(not (in? (:users %) self-id))))))

(defn- pick-ideal-article
  "Used by the classify task functions to select an article from the candidates.

  Randomly picks from the top 5% of article entries sorted by `sort-keyfn`."
  [articles sort-keyfn & [predict-run-id min-random-set]]
  (let [min-random-set (or min-random-set 100)
        n-closest (max min-random-set (quot (count articles) 20))]
    (when-let [article-id
               (->> articles
                    (sort-by sort-keyfn <)
                    (take n-closest)
                    (#(when-not (empty? %) (u/crypto-rand-nth %)))
                    :article-id)]
      (-> (article/get-article
           article-id :predict-run-id predict-run-id)
          (dissoc :raw)))))

(defn ideal-unlabeled-article
  "Selects an unlabeled article to assign to a user for classification."
  [project-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (unlabeled-articles project-id predict-run-id articles)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn ideal-single-labeled-article
  "The purpose of this function is to find articles that have a confirmed
  inclusion label from exactly one user that is not `self-id`, to present
  to `self-id` to label the article a second time.

  Articles which have any labels saved by `self-id` (even unconfirmed) will
  be excluded from this query."
  [project-id self-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (single-labeled-articles project-id self-id predict-run-id articles)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn ideal-fallback-article
  "Selects a fallback (with unconfirmed labels) article to assign to a user."
  [project-id self-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (fallback-articles project-id self-id predict-run-id articles)
     #(math/abs (- (:score %) 0.5))
     predict-run-id)))

(defn ideal-unlimited-article
  "Selects an article to assign to a user from any the user has not labeled."
  [project-id self-id & [predict-run-id articles]]
  (let [predict-run-id
        (or predict-run-id (q/project-latest-predict-run-id project-id))]
    (pick-ideal-article
     (unlimited-articles project-id self-id predict-run-id articles)
     #(count (:users %))
     predict-run-id
     50)))

(defn user-confirmed-today-count [project-id user-id]
  (-> (q/select-project-article-labels project-id true [:al.article-id])
      (q/filter-label-user user-id)
      (merge-where [:=
                    (sql/call :date_trunc "day" :al.confirm-time)
                    (sql/call :date_trunc "day" :%now)])
      (->> do-query (map :article-id) distinct count)))

(defn get-user-label-task [project-id user-id]
  (let [{:keys [second-review-prob unlimited-reviews]
         :or {second-review-prob 0.5
              unlimited-reviews false}}
        (project/project-settings project-id)
        articles (query-assignment-articles project-id)
        [unlimited pending unlabeled today-count]
        (pvalues
         (when unlimited-reviews
           (ideal-unlimited-article project-id user-id nil articles))
         (when-not unlimited-reviews
           (ideal-single-labeled-article project-id user-id nil articles))
         (when-not unlimited-reviews
           (ideal-unlabeled-article project-id nil articles))
         (user-confirmed-today-count project-id user-id))
        [article status]
        (cond
          unlimited-reviews [unlimited :unlimited]
          (and pending unlabeled)
          (if (<= (u/crypto-rand) second-review-prob)
            [pending :single] [unlabeled :unreviewed])
          pending [pending :single]
          unlabeled [unlabeled :unreviewed]
          :else
          (when-let [fallback (ideal-fallback-article project-id user-id nil articles)]
            [fallback :single]))]
    (when (and article status)
      {:article-id (:article-id article)
       :today-count today-count})))
