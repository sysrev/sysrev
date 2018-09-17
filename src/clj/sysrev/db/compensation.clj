(ns sysrev.db.compensation
  (:require [clojure.string :as string]
            [clj-time.coerce :as tc]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [honeysql.helpers :as sqlh :refer [insert-into values left-join select from where sset modifiers]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute to-jsonb sql-now]]))

;; compensation: id, rate (json ex: {:item "article" :amount 100}), created
;; project_compensation: project_id, compensation_id, active, created
;; compensation_user_period: compensation_id, web_user_id, period_begin, period_end

(defn begin-compensation-user-period!
  "Beging the compensation period for "
  [project-id compensation-id])

(defn create-project-compensation!
  "Create a compensation for project-id, where rate is a map of the form
  {:item <string> ; e.g. 'article'
   :amount <integer> ; integer amount in cents}"
  [project-id rate]
  (let [compensation-id (-> (insert-into :compensation)
                            (values [{:rate (to-jsonb rate)}])
                            (returning :id)
                            do-query first :id)]
    (-> (insert-into :compensation_project)
        (values [{:project_id project-id
                  :compensation_id compensation-id
                  :active true}])
        do-execute)
    compensation-id))

(defn read-project-compensations
  [project-id]
  (-> (select :c.id :c.rate :cp.active :c.created)
      (from [:compensation :c])
      (left-join [:compensation_project :cp]
                 [:= :c.id :cp.compensation_id])
      (where [:= :cp.project_id project-id])
      do-query))

;; move this below update-project-compensation! after this is a db call
(defn delete-project-compensation!
  [project-id compensation-id]
  #_(swap! state assoc project-id (filter #(not= (:id %) compensation-id) (get @state project-id))))

#_(defn update-project-compensation!
  [project-id compensation-id rate]
  (let [current-compensation (->> (get @state project-id)
                                  (filter #(= (:id %) compensation-id))
                                  first)
        new-compensation (assoc current-compensation :rate rate)]
    (delete-project-compensation! project-id compensation-id)
    (swap! state assoc project-id (conj (get @state project-id) new-compensation))))

(defn create-compensation-period-for-user!
  "Make an entry into compensation_user_period for compensation-id and user-id. The period_end value
  is nil and represents a currently active compensation period"
  [compensation-id user-id]
  (-> (insert-into :compensation_user_period)
      (values [{:compensation_id compensation-id
                :web_user_id user-id}])
      do-execute))

(defn end-compensation-period-for-user!
  "Mark the period_end as now for compensation-id with user-id. period_end must not already have been set"
  [compensation-id user-id]
  (-> (sqlh/update :compensation_user_period)
      (sset {:period_end (sql-now)})
      (where [:and
              [:= :compensation_id compensation-id]
              [:= :web_user_id user-id]
              [:= :period_end nil]])
      do-execute))

(defn project-users
  "Get all user-id's for project-id"
  [project-id]
  (-> (select :user_id)
      (from :project_member)
      (where [:= :project-id project-id])
      do-query))

(defn create-compensation-period-for-all-users!
  "Begin the compensation period for compensation-id for all users in project-id"
  [project-id compensation-id]
  (mapv #(create-compensation-period-for-user! compensation-id (:user-id %))
        (project-users project-id)))

(defn end-compensation-period-for-all-users!
  "End the compensation period for compensation-id for all users in project-id"
  [project-id compensation-id]
  (mapv #(end-compensation-period-for-user! compensation-id (:user-id %))
        (project-users project-id)))

(defn toggle-active-project-compensation!
  "Set active to active? on compensation-id for project-id"
  [project-id compensation-id active?]
  (-> (sqlh/update :compensation_project)
      (sset {:active active?})
      (where [:and
              [:= :project_id project-id]
              [:= :compensation_id compensation-id]])
      do-execute))

;; for now, this is just the article labeled count. Eventually, should use the "item" field of the rate on the compensation
(defn compensation-articles-for-user
  "Returns a count of articles associated with a compensation-id for user-id. start-date and end-date are of the form YYYY-MM-dd e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of the day (12:00:00AM) and end-date is until the end of the day (11:59:59AM)."
  [user-id compensation-id start-date end-date]
  (let [compensation-amount (-> (select :rate)
                                (from :compensation)
                                (where [:= :id compensation-id])
                                do-query
                                first
                                :rate
                                :amount)
        compensation-periods (-> (select :period_begin
                                         :period_end)
                                 (from :compensation_user_period)
                                 (where [:and
                                         [:= :compensation_id compensation-id]
                                         [:= :web_user_id user-id]])
                                 do-query)
        time-period-statement (->> compensation-periods
                                   (mapv #(vector :and
                                                  [:>= :al.added_time (:period-begin %)]
                                                  [:<= :al.added_time (or (:period-end %)
                                                                          (sql-now))]))
                                   (cons :or)
                                   (into []))]
    ;; check that the there is really a compensation with a time period
    (if (> (count time-period-statement) 1)
      (-> (select :%count.%distinct.al.article_id ;; :al.added_time :a.project_id
                    )
          (from [:article_label :al])
          (left-join [:article :a]
                     [:= :al.article_id :a.article_id])
          (where [:and
                  [:= :al.user_id user-id]
                  [:and
                   [:>= :al.added_time (->> start-date (f/parse (f/formatters :date)) (tc/to-sql-time))]
                   [:< :al.added_time (tc/to-sql-time (t/plus (->> end-date (f/parse (f/formatters :date))) (t/days 1)))]]
                  time-period-statement])
          do-query
          first
          :count)
      ;; there isn't any time period associated with this compensation for this user
      0
      )))


(defn project-compensation-for-user
  "Calculate the total owed to user-id by project-id. start-date and end-date are of the form YYYY-MM-dd e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of the day (12:00:00AM) and end-date is until the end of the day (11:59:59AM)."
  [project-id user-id start-date end-date]
  (let [project-compensations (read-project-compensations project-id)]
    (mapv #(hash-map :articles (compensation-articles-for-user user-id (:id %) start-date end-date)
                     :compensation-id (:id %)
                     :rate (:rate %)
                     :user-id user-id
                     :project-id project-id) project-compensations)))

(defn amount-owed
  "Return the amount-owed to users of project-id over start-date and end-date"
  [project-id start-date end-date]
  (let [project-users (-> (select :pm.user_id :wu.email)
                          (from [:project_member :pm])
                          (left-join [:web_user :wu]
                                     [:= :pm.user_id :wu.user_id])
                          (where [:= project-id :project_id])
                          do-query)
        email-user-id-map (->> project-users
                               (map #(hash-map (:user-id %) %))
                               (apply merge))]
    (->> project-users
         (map #(project-compensation-for-user project-id (:user-id %) start-date end-date))
         flatten
         (map #(assoc % :name (-> (:email (get email-user-id-map (:user-id %)))
                                   (string/split #"@")
                                   first))))))
