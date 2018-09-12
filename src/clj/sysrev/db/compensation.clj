(ns sysrev.db.compensation
  (:require [honeysql.helpers :as sqlh :refer [insert-into values left-join select from where sset]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute to-jsonb]]))

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
        do-execute)))

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
(defn toggle-active-project-compensation!
  "Set active to active? on compensation-id for project-id"
  [project-id compensation-id active?]
  (-> (sqlh/update :compensation_project)
      (sset {:active active?})
      (where [:and
              [:= :project_id project-id]
              [:= :compensation_id compensation-id]])
      do-execute))



