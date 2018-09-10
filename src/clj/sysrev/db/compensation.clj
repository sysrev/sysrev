(ns sysrev.db.compensation)

(defonce state (atom {}))

(defonce compensation-counter (atom 1))
;; compensation: id, rate (json ex: {:item "article" :amount 100}), created
;; project_compensation: project_id, compensation_id, active, created
;; compensation_user_period: compensation_id, web_user_id, period_begin, period_end

(defn create-project-compensation!
  "Create a compensation for project-id, where rate is a map of the form
  {:item <string> ; e.g. 'article'
   :amount <integer> ; integer amount in cents}"
  [project-id rate]
  (swap! compensation-counter inc)
  (swap! state assoc project-id (conj (get @state project-id) {:id @compensation-counter :rate rate :created (new java.util.Date)}))
  ;; don't forget to add to project_compensation
  )

(defn read-project-compensations
  [project-id]
  ;; this will be relying on project_compensation
  (get @state project-id))
