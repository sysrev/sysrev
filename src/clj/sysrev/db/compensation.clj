(ns sysrev.db.compensation
  (:require [clojure.string :as string]
            [clj-time.coerce :as tc]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [insert-into values left-join join select from where sset modifiers delete-from]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute to-jsonb sql-now]]
            [sysrev.util :as util]))

;; compensation: id, rate (json ex: {:item "article" :amount 100}), created
;; project_compensation: project_id, compensation_id, active, created
;; compensation_user_period: compensation_id, web_user_id, period_begin, period_end

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

(defn start-compensation-period-for-user!
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
  "A list of user for project-id"
  [project-id]
  (-> (select :pm.user_id :wu.email)
      (from [:project_member :pm])
      (left-join [:web_user :wu]
                 [:= :pm.user_id :wu.user_id])
      (where [:= project-id :project_id])
      do-query))

(defn start-compensation-period-for-all-users!
  "Begin the compensation period for compensation-id for all users in project-id"
  [project-id compensation-id]
  (mapv #(start-compensation-period-for-user! compensation-id (:user-id %))
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

(defn create-default-project-compensation!
  "Create a default project-compensation"
  [project-id compensation-id]
  (-> (insert-into :compensation_project_default)
      (values [{:project_id project-id
                :compensation_id compensation-id}])
      do-execute))

(defn get-default-project-compensation
  "Get the default compensation-id for project-id"
  [project-id]
  (-> (select :*)
      (from :compensation_project_default)
      (where [:= :project_id project-id])
      do-query
      first
      :compensation-id))

(defn delete-default-project-compensation!
  [project-id]
  (-> (delete-from :compensation_project_default)
      (where [:= :project_id project-id])
      do-execute))

(defn set-default-project-compensation!
  "Set the compensation-id for project-id to the default compensation"
  [project-id compensation-id]
  (delete-default-project-compensation! project-id)
  (create-default-project-compensation! project-id compensation-id))

;; for now, this is just the article labeled count. Eventually, should use the "item" field of the rate on the compensation
(defn compensation-owed-for-articles-for-user
  "Returns a count of articles associated with a compensation-id for user-id. start-date and end-date are of the form YYYY-MM-dd e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of the day (12:00:00AM) and end-date is until the end of the day (11:59:59AM)."
  [user-id project-id compensation-id start-date end-date]
  (let [compensation-periods (-> (select :period_begin
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
                  time-period-statement
                  [:= :a.project_id project-id]])
          do-query
          first
          :count)
      ;; there isn't any time period associated with this compensation for this user
      0)))

(defn project-compensation-for-user
  "Calculate the total owed to user-id by project-id. start-date and end-date are of the form YYYY-MM-dd e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of the day (12:00:00AM) and end-date is until the end of the day (11:59:59AM)."
  [project-id user-id start-date end-date]
  (let [project-compensations (read-project-compensations project-id)]
    (mapv #(hash-map :articles (compensation-owed-for-articles-for-user user-id project-id (:id %) start-date end-date)
                     :compensation-id (:id %)
                     :rate (:rate %)
                     :user-id user-id
                     :project-id project-id) project-compensations)))

(defn amount-owed
  "Return the amount-owed to users of project-id over start-date and end-date"
  [project-id start-date end-date]
  (let [project-users (project-users project-id)
        email-user-id-map (util/vector->hash-map project-users :user-id)]
    (->> project-users
         (map #(project-compensation-for-user project-id (:user-id %) start-date end-date))
         flatten
         (map #(assoc % :name (-> (:email (get email-user-id-map (:user-id %)))
                                   (string/split #"@")
                                   first))))))
(defn user-compensation
  "Return the current compensation_id associated with user for project-id, or nil if there is none"
  [project-id user-id]
  (-> (select :cup.compensation_id)
      (modifiers :distinct)
      (from [:compensation_user_period :cup])
      (left-join [:compensation_project :cp]
                 [:= :cp.project_id project-id])
      ;; because if period_begin is not nil
      ;; period_end is nil, the compensation is currently
      ;; active
      (where [:and
              [:<> :cup.period_begin nil]
              [:= :cup.period_end nil]
              [:= :cup.web_user_id user-id]])
      do-query
      first
      :compensation-id))

(defn project-users-current-compensation
  "Return a list of all user of a project and their current compensation-id for project-id"
  [project-id]
  (let [project-users (project-users project-id)]
    (->> project-users
        (map #(assoc % :compensation-id (user-compensation project-id (:user-id %)))))))
