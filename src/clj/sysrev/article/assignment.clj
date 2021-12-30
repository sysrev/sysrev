(ns sysrev.article.assignment
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [select limit from where order-by having]]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [clj-http.client :as http]
            [sysrev.util :as util]))

(defn record-last-assigned
  "Record that the given article has been assigned right now."
  [article-id]
  (q/modify :article {:article-id article-id} {:last-assigned :%now}))

(defn user-confirmed-today-count [project-id user-id]
  (q/find-article-label {:a.project-id project-id, :al.user-id user-id
                         (sql/call "date_trunc" "day" :al.confirm-time)
                         (sql/call "date_trunc" "day" :%now)}
                        [[:%count.%distinct.al.article-id :count]] :with [:article :label]
                        :confirmed true :filter-valid true))

(defn- query-ideal-unlimited-single [project-id user-id]
  (-> (select :article-id)
      (from [(q/find-article {:project-id project-id} [:article-id :last-assigned]
                             :where (q/not-exists [:article-label :al]
                                                  {:al.article-id :a.article-id
                                                   :al.user-id user-id})
                             :with [], :limit 50, :return :query) :sub])
      (order-by [:last-assigned :nulls-first] :%random)
      (limit 1) do-query first :article-id))

(defn- query-ideal-unlimited-double [project-id user-id]
  (-> (select :article-id)
      (from [(q/find-article {:project-id project-id} [:article-id :last-assigned]
                             :where [:and
                                     (q/exists      [:article-label :al]
                                               {:al.article-id :a.article-id})
                                     (q/not-exists  [:article-label :al]
                                                   {:al.article-id :a.article-id
                                                    :al.user-id user-id})]
                             :with [], :limit 50, :return :query) :ul])
      (order-by [:last-assigned :nulls-first] :%random)
      (limit 1) do-query first :article-id))

(defn- query-ideal-unlimited-article
  "Unlimited setting means each reviewer should review every article.
  Just pick an article the given user hasn't been reviewed by the
  given user yet."
  [project-id user-id & {:keys [second-prob] :or {second-prob 0.5}}]
  (let [single-unlimited (query-ideal-unlimited-single project-id user-id)
        double-unlimited (query-ideal-unlimited-double project-id user-id)]
    (cond (and single-unlimited
               double-unlimited) (if (<= (util/crypto-rand) second-prob)
                                   double-unlimited single-unlimited)
          single-unlimited       single-unlimited
          double-unlimited       double-unlimited
          :else                  nil)))

;TODO should this have some smart prioritization?  Would need to update the fallback-article if so.
(defn- query-ideal-single-article [project-id user-id]
  (-> (select :article-id)
      (from [(q/find-article
              {:project-id project-id} [:article-id :last-assigned]
              :where [:exists (-> (select 1)
                                  (from [:article-label :al])
                                  (where [:= :al.article-id :a.article-id])
                                  (sqlh/group :a.article-id)
                                  (having [:and
                                           [:= :%count.%distinct.user-id 1]
                                           [:= 0 (->> (sql/raw ["CASE WHEN user_id = " user-id
                                                                " THEN 1 ELSE 0 END"])
                                                      (sql/call :max))]]))]
              :with [], :limit 30, :return :query) :sub])
      (order-by [:last-assigned :nulls-first] :%random)
      (limit 1) do-query first :article-id))

(defn- query-any-unlabeled-article [project-id]
  (-> (select :article-id)
      (from [(q/find-article {:project-id project-id} [:article-id :last-assigned]
                             :where (q/not-exists [:article-label :al]
                                                  {:al.article-id :a.article-id})
                             :with [], :limit 30, :return :query) :sub])
      (order-by [:last-assigned :nulls-first] :%random)
      (limit 1) do-query first :article-id))

;; TODO: does this always return the single highest scoring article?
(defn- query-ideal-unlabeled-article [project-id]
  (if-let [predict-run-id (q/project-latest-predict-run-id project-id)]
    (some->> (-> (select :article-id :val)
                 (from [(q/find-article
                         {:a.project-id project-id :lp.predict-run-id predict-run-id}
                         [:a.article-id :a.last-assigned :lp.val]
                         :left-join [[:label-predicts :lp] :a.article-id]
                         :where (q/not-exists [:article-label :al]
                                              {:al.article-id :a.article-id})
                         :with [], :limit 100, :return :query) :sub])
                 (order-by [:last-assigned :nulls-first] :%random)
                 (limit 30) do-query seq)
             (reduce (fn [a b] (if (> (Math/abs (- (or (:val a) 0.0) 0.5))
                                      (Math/abs (- (or (:val b) 0.0) 0.5)))
                                 b a)))
             :article-id)
    (query-any-unlabeled-article project-id)))

(defn- query-ideal-fallback-article
  "This is an emergency case where other ideal article methods fail.
  Picks a random unlabeled article, or single labeled article."
  [project-id]
  (query-any-unlabeled-article project-id))

(defn get-user-label-task-internal [project-id user-id]
  (let [{:keys [second-review-prob unlimited-reviews]
         :or   {second-review-prob 0.5
                unlimited-reviews false}} (project/project-settings project-id)
        unlimited? unlimited-reviews
        [unlimited single-label unlabeled today-count]
        (pvalues (when unlimited?     (query-ideal-unlimited-article
                                       project-id user-id :second-prob second-review-prob))
                 (when-not unlimited? (query-ideal-single-article project-id user-id))
                 (when-not unlimited? (query-ideal-unlabeled-article project-id))
                 (user-confirmed-today-count project-id user-id))
        both? (and single-label unlabeled)
        [article-id status]
        (cond unlimited?    [unlimited :unlimited]
              both?         (if (<= (util/crypto-rand) second-review-prob)
                              [single-label :single]
                              [unlabeled :unreviewed])
              single-label  [single-label :single]
              unlabeled     [unlabeled :unreviewed]
              :else         (when-let [fallback (query-ideal-fallback-article project-id)]
                              [fallback :unreviewed]))]
    (when (and article-id status)
      {:article-id article-id :today-count today-count})))

(defn get-user-label-task-external [project-id user-id custom-prioritization-url]
  (let [today-count (user-confirmed-today-count project-id user-id)
        response (-> (http/get custom-prioritization-url
                               {:query-params {:pid project-id
                                               :uid user-id}
                                :as :json})
                     :body)
        article-id (->
                     (select :article-id)
                     (from :article)
                     (where [:= :article-id (first (:aid response))])
                     (limit 1) do-query first :article-id)]
    (when article-id
      {:article-id article-id :today-count today-count})))

(defn get-user-label-task [project-id user-id]
  (let [project-settings (project/project-settings project-id)]
    (or
      (when-let [custom-prioritization-url (:custom-prioritization-url project-settings)]
        (try
          (get-user-label-task-external project-id user-id custom-prioritization-url)
          (catch Exception _ nil)))
      (get-user-label-task-internal project-id user-id))))
