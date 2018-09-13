(ns sysrev.db.compensation
  (:require [honeysql.helpers :as sqlh :refer [insert-into values left-join select from where sset]]
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
